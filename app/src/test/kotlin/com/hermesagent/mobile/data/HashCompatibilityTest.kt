package com.hermesagent.mobile.data

import com.hermesagent.mobile.data.composer.ComposerQueueScope
import com.hermesagent.mobile.data.gateway.AndroidGatewayTokenStore
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.plugins.groups.storedUserEventId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden outputs captured before extraction; identifiers are synthetic, never credentials. */
class HashCompatibilityTest {
    @Test fun emptyDigestVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Utf8Hex(""))
    }

    @Test fun queueUtf8Vectors() {
        val vectors = listOf(
            "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "café雪🙂" to "22dcc358df054a0bd9e572e5e640bca1e2f40c0c3d67fac83104b79e06be3c32",
            "a\u0000b" to "59b271ae1bbcb1d31d41929817f4b16fb439eb4f31520b5ad1d5ce98920a7138",
        )
        vectors.forEach { (input, hash) ->
            assertEquals("composer_queue_$hash.preferences_pb", ComposerQueueScope(input).storageFileName())
        }
    }

    @Test fun persistedScopeVectors() {
        assertEquals("9a21d4f6b97dc3c81da735a57a62db3cccb90ae35ec41ed4f88f7f608b0565ff",
            ComposerControlsScope("transient", "default").storageKey())
        assertEquals("0712058e88a47e536e5538a4a3bf221487a4e5b804afcf17eea1d9c75a54d3d7",
            ComposerControlsScope("row", "profile").storageKey())
        assertEquals("composer_queue_0712058e88a47e536e5538a4a3bf221487a4e5b804afcf17eea1d9c75a54d3d7.preferences_pb",
            ComposerQueueScope.forConnectionProfile("row", "profile").storageFileName())
        assertEquals("composer_queue_12ef565ec0f30314dfb671049eb921a6d6dcebd6aa62ddbea813a1084a2eaaba.preferences_pb",
            ComposerQueueScope.forConnectionProfile("row", "profile", " 雪 ").storageFileName())
        assertEquals("connection\u0000row", AndroidGatewayTokenStore.slotDigestInput("row"))
    }

    @Test fun protocolEventVector() {
        assertEquals("user:ce36863f51b6baf9d16397ffb3e9af506b284a816f72d487e55943c1fd974d6d",
            storedUserEventId("event-1"))
    }
}
