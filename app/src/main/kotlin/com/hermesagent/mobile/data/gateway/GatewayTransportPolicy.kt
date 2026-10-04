package com.hermesagent.mobile.data.gateway

/** Shared by the process graph and manager defaults; inherited by every derived client. */
internal fun gatewayTransportClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
    // HTTP(S) hostnames and IPs follow Desktop input semantics. HTTP is unencrypted.
    // Keep existing auth safeguards: do not move credentials to redirect/proxy recipients.
    .followRedirects(false)
    .followSslRedirects(false)
    .proxy(java.net.Proxy.NO_PROXY)
    .build()
