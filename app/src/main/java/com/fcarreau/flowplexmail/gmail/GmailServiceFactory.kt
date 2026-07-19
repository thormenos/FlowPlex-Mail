package com.fcarreau.flowplexmail.gmail

import android.content.Context
import com.google.api.client.extensions.android.json.AndroidJsonFactory
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.services.gmail.Gmail

object GmailServiceFactory {

    fun credentialFor(context: Context, accountName: String): GoogleAccountCredential {
        val credential = GoogleAccountCredential.usingOAuth2(context, REQUIRED_SCOPES)
        credential.selectedAccountName = accountName
        return credential
    }

    fun build(context: Context, accountName: String): Gmail {
        return Gmail.Builder(NetHttpTransport(), AndroidJsonFactory.getDefaultInstance(), credentialFor(context, accountName))
            .setApplicationName("FlowPlex.mail")
            .build()
    }
}
