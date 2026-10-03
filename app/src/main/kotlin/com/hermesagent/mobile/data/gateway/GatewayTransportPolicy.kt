package com.hermesagent.mobile.data.gateway

/**
 * Numeric RFC1918, CGNAT (Tailnet), and IPv6 ULA addresses. Never resolve DNS
 * to decide trust: a name can change between validation and socket creation.
 * Private does not mean encrypted; HTTPS remains preferred even on a LAN.
 */
internal fun isPrivateGatewayHost(host: String): Boolean {
    if (':' in host) {
        val bytes = runCatching { java.net.InetAddress.getByName(host).address }.getOrNull() ?: return false
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 ||
        (parts[0] == 172 && parts[1] in 16..31) ||
        (parts[0] == 192 && parts[1] == 168) ||
        (parts[0] == 100 && parts[1] in 64..127)
}

/** Shared by the process graph and manager defaults; inherited by every derived client. */
internal fun gatewayTransportClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
    // Android XML cannot express private IP ranges; reject before any network I/O.
    .addInterceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps && url.host !in setOf("127.0.0.1", "localhost", "::1") &&
            !isPrivateGatewayHost(url.host)
        ) {
            throw java.io.IOException("Use HTTPS or a private Gateway IP address.")
        }
        chain.proceed(chain.request())
    }
    .followRedirects(false)
    .followSslRedirects(false)
    // No public/system proxy may receive private-hop credentials.
    .proxy(java.net.Proxy.NO_PROXY)
    .build()

