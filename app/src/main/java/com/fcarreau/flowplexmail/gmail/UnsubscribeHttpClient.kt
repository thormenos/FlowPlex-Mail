package com.fcarreau.flowplexmail.gmail

import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport

interface UnsubscribeHttpClient {
    fun get(url: String)
    fun postOneClick(url: String)
}

class NetUnsubscribeHttpClient : UnsubscribeHttpClient {
    private val requestFactory = NetHttpTransport().createRequestFactory()

    override fun get(url: String) {
        requestFactory.buildGetRequest(GenericUrl(url)).execute()
    }

    override fun postOneClick(url: String) {
        val body = ByteArrayContent.fromString("application/x-www-form-urlencoded", "List-Unsubscribe=One-Click")
        requestFactory.buildPostRequest(GenericUrl(url), body).execute()
    }
}
