package com.fcarreau.flowplexmail

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.fcarreau.flowplexmail.data.AccountPrefs
import com.fcarreau.flowplexmail.data.CategoryCount
import com.fcarreau.flowplexmail.data.FlowPlexDatabase
import com.fcarreau.flowplexmail.data.MessageEntity
import com.fcarreau.flowplexmail.data.SenderGroupCount
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
    private var openDomain by mutableStateOf<String?>(null)
    private var processingMessageId by mutableStateOf<String?>(null)
    private var processingDomain by mutableStateOf<String?>(null)
    private var isBulkProcessing by mutableStateOf(false)
    private var selectedIds by mutableStateOf<Set<String>>(emptySet())
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
                val domain = openDomain

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        connectedEmail == null -> SignedOutScreen(onSignIn = { signInLauncher.launch(googleSignInClient.signInIntent) })

                        category != null && domain != null -> {
                            BackHandler { closeDomain() }
                            val meta = CATEGORY_META.getValue(category)
                            val messages by messageDao.observePendingByCategoryAndDomain(category, domain).collectAsState(initial = emptyList())
                            val title = messages.firstOrNull()?.senderDisplayName ?: domain
                            CategoryReviewScreen(
                                title = "${meta.emoji} $title",
                                messages = messages,
                                selectedIds = selectedIds,
                                processingMessageId = processingMessageId,
                                isBulkProcessing = isBulkProcessing,
                                onBack = { closeDomain() },
                                onToggleSelect = { id ->
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                onClearSelection = { selectedIds = emptySet() },
                                onTrash = { performAction(it) { m -> actionRepository().trash(m) } },
                                onUnsubscribe = { performAction(it) { m -> actionRepository().unsubscribe(m) } },
                                onBulkTrash = {
                                    val selected = messages.filter { it.id in selectedIds }
                                    performBulkAction { actionRepository().trashAll(selected) }
                                },
                                onBulkUnsubscribe = {
                                    val selected = messages.filter { it.id in selectedIds }
                                    performBulkAction { actionRepository().unsubscribeAll(selected) }
                                },
                                onBulkIgnore = {
                                    val selected = messages.filter { it.id in selectedIds }
                                    performBulkAction { actionRepository().ignoreAll(selected) }
                                },
                            )
                        }

                        category != null -> {
                            BackHandler { closeCategory() }
                            val meta = CATEGORY_META.getValue(category)
                            val groups by messageDao.observeSenderGroups(category).collectAsState(initial = emptyList())
                            SenderGroupListScreen(
                                meta = meta,
                                groups = groups,
                                processingDomain = processingDomain,
                                onBack = { closeCategory() },
                                onOpenDomain = { d -> selectedIds = emptySet(); openDomain = d },
                                onTrashGroup = { d -> performGroupAction(category, d) { list -> actionRepository().trashAll(list) } },
                                onUnsubscribeGroup = { d -> performGroupAction(category, d) { list -> actionRepository().unsubscribeAll(list) } },
                            )
                        }

                        else -> DashboardScreen(
                            email = connectedEmail,
                            profileSummary = profileSummary,
                            errorText = errorText,
                            categoryCounts = categoryCounts,
                            isSyncing = isSyncing,
                            onSyncNow = { SyncWorker.enqueueNow(this) },
                            onOpenCategory = { selectedIds = emptySet(); openCategory = it },
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

    private fun closeCategory() {
        openCategory = null
        openDomain = null
        selectedIds = emptySet()
    }

    private fun closeDomain() {
        openDomain = null
        selectedIds = emptySet()
    }

    private fun performBulkAction(block: suspend () -> Unit) {
        isBulkProcessing = true
        lifecycleScope.launch {
            try {
                block()
                selectedIds = emptySet()
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Échec: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isBulkProcessing = false
            }
        }
    }

    private fun performGroupAction(category: String, domain: String, block: suspend (List<MessageEntity>) -> Unit) {
        processingDomain = domain
        lifecycleScope.launch {
            try {
                val messages = messageDao.getPendingByCategoryAndDomain(category, domain)
                block(messages)
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Échec: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                processingDomain = null
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
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "✨ Boîte propre en un coup d'œil",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                profileSummary?.let {
                    Text(it, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    "$totalPending emails à trier · $totalUnsubscribable désabonnements possibles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                errorText?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onSyncNow, enabled = !isSyncing, shape = MaterialTheme.shapes.large) {
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

private val ACTION_ICON_SIZE = 18.dp

private fun faviconUrl(domain: String) = "https://www.google.com/s2/favicons?sz=64&domain=$domain"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SenderGroupListScreen(
    meta: CategoryMeta,
    groups: List<SenderGroupCount>,
    processingDomain: String?,
    onBack: () -> Unit,
    onOpenDomain: (String) -> Unit,
    onTrashGroup: (String) -> Unit,
    onUnsubscribeGroup: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("${meta.emoji} ${meta.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (groups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SwipeHintLegend()
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
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
                items(groups, key = { it.senderDomain }) { group ->
                    SenderGroupRow(
                        group = group,
                        isProcessing = processingDomain == group.senderDomain,
                        onOpen = { onOpenDomain(group.senderDomain) },
                        onTrash = { onTrashGroup(group.senderDomain) },
                        onUnsubscribe = { onUnsubscribeGroup(group.senderDomain) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SenderGroupRow(
    group: SenderGroupCount,
    isProcessing: Boolean,
    onOpen: () -> Unit,
    onTrash: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val context = LocalContext.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onTrash()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (group.unsubscribableCount > 0) {
                        onUnsubscribe()
                        true
                    } else {
                        Toast.makeText(context, "Aucun lien de désabonnement dans ce groupe", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    val cardContent: @Composable () -> Unit = {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = faviconUrl(group.senderDomain),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.senderDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (isProcessing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(ACTION_ICON_SIZE), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("En cours…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            "${group.count} emails · ${group.unsubscribableCount} désabonnements possibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (isProcessing) {
        cardContent()
    } else {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = group.unsubscribableCount > 0,
            backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
            content = { cardContent() },
        )
    }
}

@Composable
private fun CategoryReviewScreen(
    title: String,
    messages: List<MessageEntity>,
    selectedIds: Set<String>,
    processingMessageId: String?,
    isBulkProcessing: Boolean,
    onBack: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onTrash: (MessageEntity) -> Unit,
    onUnsubscribe: (MessageEntity) -> Unit,
    onBulkTrash: () -> Unit,
    onBulkUnsubscribe: () -> Unit,
    onBulkIgnore: () -> Unit,
) {
    val selectionMode = selectedIds.isNotEmpty()
    val selectedUnsubscribableCount = messages.count { it.id in selectedIds && it.hasListUnsubscribe }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = if (selectionMode) onClearSelection else onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Spacer(modifier = Modifier.width(4.dp))
            if (selectionMode) {
                Text("${selectedIds.size} sélectionné(s)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            } else {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        if (selectionMode) {
            Spacer(modifier = Modifier.height(12.dp))
            if (isBulkProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(ACTION_ICON_SIZE), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Traitement en cours…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(icon = Icons.Filled.Delete, label = "Corbeille", tint = MaterialTheme.colorScheme.error, onClick = onBulkTrash)
                    if (selectedUnsubscribableCount > 0) {
                        ActionButton(icon = Icons.Filled.NotificationsOff, label = "Se désabonner", onClick = onBulkUnsubscribe)
                    }
                    ActionButton(icon = Icons.Filled.Check, label = "Garder", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onBulkIgnore)
                }
            }
        }

        if (!selectionMode && messages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            SwipeHintLegend()
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
                        isSelected = message.id in selectedIds,
                        selectionMode = selectionMode,
                        isProcessing = processingMessageId == message.id,
                        onToggleSelect = { onToggleSelect(message.id) },
                        onTrash = { onTrash(message) },
                        onUnsubscribe = { onUnsubscribe(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeHintLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Corbeille", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Se désabonner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Filled.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondary,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ACTION_ICON_SIZE))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageReviewCard(
    message: MessageEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    isProcessing: Boolean,
    onToggleSelect: () -> Unit,
    onTrash: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val context = LocalContext.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onTrash()
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (message.hasListUnsubscribe) {
                        onUnsubscribe()
                        true
                    } else {
                        Toast.makeText(context, "Pas de lien de désabonnement pour cet email", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    val cardContent: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(message.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(modifier = Modifier.height(2.dp))
                    if (isProcessing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(ACTION_ICON_SIZE), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("En cours…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(message.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (selectionMode || isProcessing) {
        cardContent()
    } else {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = message.hasListUnsubscribe,
            backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
            content = { cardContent() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.error, Icons.Filled.Delete, Alignment.CenterEnd)
        SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.secondary, Icons.Filled.NotificationsOff, Alignment.CenterStart)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(MaterialTheme.shapes.medium)
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Color.White) }
    }
}
