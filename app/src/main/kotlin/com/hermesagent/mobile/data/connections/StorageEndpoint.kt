package com.hermesagent.mobile.data.connections

/** Atomic saved-row/address identity paired with the cache generation by the switch owner. */
data class StorageEndpoint(val stableId: String, val generation: Long)
