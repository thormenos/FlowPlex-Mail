package com.fcarreau.flowplexmail.gmail

import com.google.api.services.drive.DriveScopes
import com.google.api.services.gmail.GmailScopes

val REQUIRED_SCOPES: List<String> = listOf(
    GmailScopes.GMAIL_MODIFY,
    GmailScopes.GMAIL_SETTINGS_BASIC,
    GmailScopes.GMAIL_SEND,
    DriveScopes.DRIVE_METADATA_READONLY,
)
