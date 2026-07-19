package com.fcarreau.flowplexmail.gmail

import java.net.URLDecoder

sealed class UnsubscribeTarget {
    data class Http(val url: String, val oneClickPost: Boolean) : UnsubscribeTarget()
    data class Mailto(val address: String, val subject: String?) : UnsubscribeTarget()
}

/**
 * Analyse le header "List-Unsubscribe" (RFC 2369), ex :
 * "<https://exemple.com/unsub?id=1>, <mailto:unsub@exemple.com?subject=stop>"
 * Le lien HTTP est privilégié ; s'il est accompagné de "List-Unsubscribe-Post: List-Unsubscribe=One-Click"
 * (RFC 8058), la requête doit être un POST plutôt qu'un GET.
 */
fun parseListUnsubscribe(header: String?, postHeader: String?): UnsubscribeTarget? {
    if (header.isNullOrBlank()) return null
    val tokens = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()

    tokens.firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        ?.let { url ->
            val oneClick = postHeader?.trim()?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true
            return UnsubscribeTarget.Http(url, oneClick)
        }

    tokens.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.let { mailto ->
        val uri = mailto.removePrefix("mailto:")
        val parts = uri.split("?", limit = 2)
        val address = parts[0]
        var subject: String? = null
        if (parts.size > 1) {
            for (param in parts[1].split("&")) {
                val kv = param.split("=", limit = 2)
                if (kv.size == 2 && kv[0].equals("subject", ignoreCase = true)) {
                    subject = runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
                }
            }
        }
        return UnsubscribeTarget.Mailto(address, subject)
    }

    return null
}
