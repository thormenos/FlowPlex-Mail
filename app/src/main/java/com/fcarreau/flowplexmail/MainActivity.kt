package com.fcarreau.flowplexmail

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fcarreau.flowplexmail.data.AccountPrefs
import com.fcarreau.flowplexmail.data.CategoryCount
import com.fcarreau.flowplexmail.data.FlowPlexDatabase
import com.fcarreau.flowplexmail.data.MessageEntity
import com.fcarreau.flowplexmail.gmail.GmailServiceFactory
import com.fcarreau.flowplexmail.gmail.MessageActionRepository
import com.fcarreau.flowplexmail.gmail.REQUIRED_SCOPES
import com.fcarreau.flowplexmail.sync.SyncWorker
import com.fcarreau.flowplexmail.ui.theme.FlowPlexMailTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CATEGORY_META = mapOf(
    "promotions" to CategoryMeta("🛍️", "Promotions"),
    "social" to CategoryMeta("👥", "Réseaux sociaux"),
    "updates" to CategoryMeta("🔔", "Mises à jour"),
    "forums" to CategoryMeta("💬", "Forums"),
)

private data class CategoryMeta(val emoji: String, val label: String)

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private val messageDao by lazy { FlowPlexDatabase.getInstance(this).messageDao() }

    private var connectedEmail by mutableStateOf<String?>(null)
    private var profileSummary by mutableStateOf<String?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var openCategory by mutableStateOf<String?>(null)
    private var processingMessageId by mutableStateOf<String?>(null)
    private var pendingAccountName: String? = null

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingAccountName?.let { fetchProfile(it) }
        } else {
            errorText = "Consentement refusé, réessayez"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(REQUIRED_SCOPES[0]), *REQUIRED_SCOPES.drop(1).map { Scope(it) }.toTypedArray())
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            FlowPlexMailTheme {
                val categoryCounts by messageDao.observeCategoryCounts().collectAsState(initial = emptyList())
                val isSyncing by SyncWorker.observeIsSyncing(this).collectAsState(initial = false)
                val category = openCategory

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        connectedEmail == null -> SignedOutScreen(onSignIn = { signInLauncher.launch(googleSignInClient.signInIntent) })

                        category != null -> {
                            BackHandler { openCategory = null }
                            val meta = CATEGORY_META.getValue(category)
                            val messages by messageDao.observePendingByCategory(category).collectAsState(initial = emptyList())
                            CategoryReviewScreen(
                                meta = meta,
                                messages = messages,
                                processingMessageId = processingMessageId,
                                onBack = { openCategory = null },
                                onTrash = { performAction(it) { m -> actionRepository().trash(m) } },
                                onUnsubscribe = { performAction(it) { m -> actionRepository().unsubscribe(m) } },
                                onIgnore = { performAction(it) { m -> actionRepository().ignore(m) } },
                            )
                        }

                        else -> DashboardScreen(
                            email = connectedEmail,
                            profileSummary = profileSummary,
                            errorText = errorText,
                            categoryCounts = categoryCounts,
                            isSyncing = isSyncing,
                            onSyncNow = { SyncWorker.enqueueNow(this) },
                            onOpenCategory = { openCategory = it },
                        )
                    }
                }
            }
        }

        GoogleSignIn.getLastSignedInAccount(this)?.let { account -> onAccountReady(account) }
    }

    private fun handleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            onAccountReady(account)
        } catch (e: ApiException) {
            errorText = "Échec de connexion (code ${e.statusCode})"
        }
    }

    private fun onAccountReady(account: GoogleSignInAccount) {
        val accountName = account.email ?: return
        connectedEmail = accountName
        AccountPrefs.save(this, accountName)
        pendingAccountName = accountName
        fetchProfile(accountName)
        SyncWorker.enqueuePeriodic(this)
        SyncWorker.enqueueNow(this)
    }

    private fun fetchProfile(accountName: String) {
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val gmail = GmailServiceFactory.build(this@MainActivity, accountName)
                    val profile = gmail.users().getProfile("me").execute()
                    "${profile.messagesTotal} messages · ${profile.threadsTotal} conversations"
                }
                profileSummary = summary
                errorText = null
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                errorText = "Erreur: ${e.message}"
            }
        }
    }

    private fun actionRepository(): MessageActionRepository {
        val accountName = connectedEmail ?: error("Non connecté")
        val gmail = GmailServiceFactory.build(this, accountName)
        return MessageActionRepository(gmail, messageDao, accountName)
    }

    private fun performAction(message: MessageEntity, block: suspend (MessageEntity) -> Unit) {
        processingMessageId = message.id
        lifecycleScope.launch {
            try {
                block(message)
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Échec: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                processingMessageId = null
            }
        }
    }
}

@Composable
private fun SignedOutScreen(onSignIn: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("📬", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "FlowPlex.mail",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Gardez votre boîte Gmail légère et organisée, sans effort.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("Se connecter avec Google", modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun DashboardScreen(
    email: String?,
    profileSummary: String?,
    errorText: String?,
    categoryCounts: List<CategoryCount>,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val totalPending = categoryCounts.sumOf { it.count }
    val totalUnsubscribable = categoryCounts.sumOf { it.unsubscribableCount }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📬", fontSize = 32.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("FlowPlex.mail", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "✨ Boîte propre en un coup d'œil",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                profileSummary?.let {
                    Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    "$totalPending emails à trier · $totalUnsubscribable désabonnements possibles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                errorText?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = onSyncNow, enabled = !isSyncing, shape = MaterialTheme.shapes.large) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Synchronisation…")
                    } else {
                        Text("🔄 Synchroniser maintenant")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Par catégorie — appuyez pour trier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        val countsByCategory = categoryCounts.associateBy { it.category }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CATEGORY_META.entries.toList()) { (key, meta) ->
                val stat = countsByCategory[key]
                CategoryCard(
                    meta = meta,
                    count = stat?.count ?: 0,
                    unsubscribable = stat?.unsubscribableCount ?: 0,
                    onClick = { onOpenCategory(key) },
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(meta: CategoryMeta, count: Int, unsubscribable: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(meta.emoji, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(meta.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (count == 0) "Rien à signaler" else "$count emails · $unsubscribable désabonnements possibles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryReviewScreen(
    meta: CategoryMeta,
    messages: List<MessageEntity>,
    processingMessageId: String?,
    onBack: () -> Unit,
    onTrash: (MessageEntity) -> Unit,
    onUnsubscribe: (MessageEntity) -> Unit,
    onIgnore: (MessageEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("←", fontSize = 24.sp) }
            Spacer(modifier = Modifier.width(4.dp))
            Text("${meta.emoji} ${meta.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🎉", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Rien à trier ici, tout est propre !", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages, key = { it.id }) { message ->
                    MessageReviewCard(
                        message = message,
                        isProcessing = processingMessageId == message.id,
                        onTrash = { onTrash(message) },
                        onUnsubscribe = { onUnsubscribe(message) },
                        onIgnore = { onIgnore(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageReviewCard(
    message: MessageEntity,
    isProcessing: Boolean,
    onTrash: () -> Unit,
    onUnsubscribe: () -> Unit,
    onIgnore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(message.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(message.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            if (isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("En cours…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onTrash, shape = MaterialTheme.shapes.medium) {
                        Text("🗑️ Corbeille")
                    }
                    if (message.hasListUnsubscribe) {
                        OutlinedButton(onClick = onUnsubscribe, shape = MaterialTheme.shapes.medium) {
                            Text("🚫 Se désabonner")
                        }
                    }
                    TextButton(onClick = onIgnore) {
                        Text("Garder")
                    }
                }
            }
        }
    }
}
