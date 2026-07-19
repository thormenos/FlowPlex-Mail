package com.fcarreau.flowplexmail

import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
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
    "promotions" to CategoryMeta("🛍️", R.string.category_promotions),
    "social" to CategoryMeta("👥", R.string.category_social),
    "updates" to CategoryMeta("🔔", R.string.category_updates),
    "forums" to CategoryMeta("💬", R.string.category_forums),
)

private data class CategoryMeta(val emoji: String, @StringRes val labelRes: Int)

private const val TAG = "FlowPlexMail"

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private val messageDao by lazy { FlowPlexDatabase.getInstance(this).messageDao() }

    private var connectedEmail by mutableStateOf<String?>(null)
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
            Toast.makeText(this, getString(R.string.error_consent_denied), Toast.LENGTH_SHORT).show()
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
                                onTrashGroup = { d ->
                                    performDomainAction(d) { actionRepository().trashAllForDomain(category, d) }
                                },
                                onUnsubscribeGroup = { d ->
                                    performDomainAction(d) {
                                        val sample = messageDao.getPendingByCategoryAndDomain(category, d)
                                            .firstOrNull { it.hasListUnsubscribe }
                                        if (sample == null) {
                                            Toast.makeText(this@MainActivity, getString(R.string.error_no_unsubscribe_link), Toast.LENGTH_SHORT).show()
                                        } else {
                                            val result = actionRepository().unsubscribeAndTrashDomain(category, d, sample)
                                            val message = if (result.unsubscribed) {
                                                getString(R.string.result_unsubscribed_format, result.trashedCount)
                                            } else {
                                                getString(R.string.result_unsubscribe_failed_format, result.trashedCount)
                                            }
                                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            )
                        }

                        else -> DashboardScreen(
                            email = connectedEmail,
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
            Toast.makeText(this, getString(R.string.error_sign_in_failed_format, e.statusCode), Toast.LENGTH_SHORT).show()
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

    /** Valide la connexion et déclenche l'écran de consentement des scopes Gmail/Drive si besoin. */
    private fun fetchProfile(accountName: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val gmail = GmailServiceFactory.build(this@MainActivity, accountName)
                    gmail.users().getProfile("me").execute()
                }
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                Log.e(TAG, "fetchProfile a échoué", e)
                Toast.makeText(this@MainActivity, getString(R.string.error_generic_format, e.message), Toast.LENGTH_SHORT).show()
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
                Log.e(TAG, "Action sur le message ${message.id} a échoué", e)
                Toast.makeText(this@MainActivity, getString(R.string.error_action_failed_format, e.message), Toast.LENGTH_SHORT).show()
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
                Log.e(TAG, "Action groupée a échoué", e)
                Toast.makeText(this@MainActivity, getString(R.string.error_action_failed_format, e.message), Toast.LENGTH_SHORT).show()
            } finally {
                isBulkProcessing = false
            }
        }
    }

    private fun performDomainAction(domain: String, block: suspend () -> Unit) {
        processingDomain = domain
        lifecycleScope.launch {
            try {
                block()
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                Log.e(TAG, "Action sur le domaine $domain a échoué", e)
                Toast.makeText(this@MainActivity, getString(R.string.error_action_failed_format, e.message), Toast.LENGTH_SHORT).show()
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
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Text(stringResource(R.string.sign_in_with_google), modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun DashboardScreen(
    email: String?,
    categoryCounts: List<CategoryCount>,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    val totalPending = categoryCounts.sumOf { it.count }
    val totalUnsubscribable = categoryCounts.sumOf { it.unsubscribableCount }

    LaunchedEffect(Unit) { onSyncNow() }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📬", fontSize = 32.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    stringResource(R.string.email_summary_format, totalPending, totalUnsubscribable),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (isSyncing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.syncing_in_progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(stringResource(R.string.category_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                Text(stringResource(meta.labelRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (count == 0) stringResource(R.string.nothing_to_report) else stringResource(R.string.email_summary_format, count, unsubscribable),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text("${meta.emoji} ${stringResource(meta.labelRes)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                Text(stringResource(R.string.nothing_to_sort), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Toast.makeText(context, context.getString(R.string.error_no_unsubscribe_link_group), Toast.LENGTH_SHORT).show()
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
                            Text(stringResource(R.string.item_processing), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(
                            stringResource(R.string.email_summary_format, group.count, group.unsubscribableCount),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
            }
            Spacer(modifier = Modifier.width(4.dp))
            if (selectionMode) {
                Text(stringResource(R.string.selected_count_format, selectedIds.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    Text(stringResource(R.string.bulk_processing), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(icon = Icons.Filled.Delete, label = stringResource(R.string.action_trash), tint = MaterialTheme.colorScheme.error, onClick = onBulkTrash)
                    if (selectedUnsubscribableCount > 0) {
                        ActionButton(icon = Icons.Filled.NotificationsOff, label = stringResource(R.string.action_unsubscribe), onClick = onBulkUnsubscribe)
                    }
                    ActionButton(icon = Icons.Filled.Check, label = stringResource(R.string.action_keep), tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onBulkIgnore)
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
                Text(stringResource(R.string.nothing_to_sort), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(stringResource(R.string.action_trash), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.action_unsubscribe), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Toast.makeText(context, context.getString(R.string.error_no_unsubscribe_link_message), Toast.LENGTH_SHORT).show()
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
                            Text(stringResource(R.string.item_processing), style = MaterialTheme.typography.bodySmall)
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
