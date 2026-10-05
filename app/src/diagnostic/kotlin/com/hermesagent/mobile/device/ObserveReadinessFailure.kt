package com.hermesagent.mobile.device

internal inline fun observeReadinessFailure(observer: () -> Unit, readiness: () -> Unit) {
    try {
        readiness()
    } catch (original: Throwable) {
        try { observer() } catch (_: Throwable) { /* Evidence must not replace failure. */ }
        throw original
    }
}
