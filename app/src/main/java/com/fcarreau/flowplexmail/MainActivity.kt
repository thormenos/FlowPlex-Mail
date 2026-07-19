package com.fcarreau.flowplexmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.client.extensions.android.json.AndroidJsonFactory
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.services.drive.DriveScopes
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val REQUIRED_SCOPES = listOf(
    GmailScopes.GMAIL_MODIFY,
    GmailScopes.GMAIL_SETTINGS_BASIC,
    GmailScopes.GMAIL_SEND,
    DriveScopes.DRIVE_METADATA_READONLY,
)

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private var statusText by mutableStateOf("Non connecté")
    private var pendingCredential: GoogleAccountCredential? = null

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingCredential?.let { fetchProfile(it) }
        } else {
            statusText = "Consentement refusé, réessayez"
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("FlowPlex.mail", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(statusText)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { signInLauncher.launch(googleSignInClient.signInIntent) }) {
                            Text("Se connecter avec Google")
                        }
                    }
                }
            }
        }

        GoogleSignIn.getLastSignedInAccount(this)?.let { account -> startProfileFetch(account) }
    }

    private fun handleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            statusText = "Connecté: ${account.email}\nRécupération du profil Gmail..."
            startProfileFetch(account)
        } catch (e: ApiException) {
            statusText = "Échec de connexion (code ${e.statusCode})"
        }
    }

    private fun startProfileFetch(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(this, REQUIRED_SCOPES)
        credential.selectedAccount = account.account
        pendingCredential = credential
        fetchProfile(credential)
    }

    private fun fetchProfile(credential: GoogleAccountCredential) {
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val gmail = Gmail.Builder(NetHttpTransport(), AndroidJsonFactory.getDefaultInstance(), credential)
                        .setApplicationName("FlowPlex.mail")
                        .build()
                    val profile = gmail.users().getProfile("me").execute()
                    "Connecté: ${profile.emailAddress}\n${profile.messagesTotal} messages, ${profile.threadsTotal} conversations"
                }
                statusText = summary
            } catch (e: UserRecoverableAuthIOException) {
                consentLauncher.launch(e.intent)
            } catch (e: Exception) {
                statusText = "Erreur: ${e.message}"
            }
        }
    }
}
