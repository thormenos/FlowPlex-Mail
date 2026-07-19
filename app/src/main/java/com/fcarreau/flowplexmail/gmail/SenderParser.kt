package com.fcarreau.flowplexmail.gmail

data class SenderInfo(val displayName: String, val domain: String)

private val ANGLE_EMAIL = Regex("<([^>]+)>")

/**
 * Analyse un header "From" du type `"Boutique" <no-reply@boutique.com>` en nom
 * affichable + domaine, pour pouvoir regrouper les emails par expéditeur/domaine.
 */
fun parseSender(fromHeader: String): SenderInfo {
    val match = ANGLE_EMAIL.find(fromHeader)
    val email = match?.groupValues?.get(1)?.trim() ?: fromHeader.trim()
    val domain = email.substringAfter('@', "").lowercase().trim()

    val rawName = if (match != null) fromHeader.substring(0, match.range.first) else ""
    val displayName = rawName.trim().trim('"', '\'').ifBlank { domain.ifBlank { email } }

    return SenderInfo(displayName = displayName, domain = domain)
}
