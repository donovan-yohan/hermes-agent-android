package com.hermesagent.mobile.ui.common

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Bounded bitmap decoding for attachment previews. Both entry points decode
 * from in-memory bytes only — no path, no URI, no persistence — and cap the
 * decoded dimensions so an 8 MB photo cannot become a ~100 MB bitmap.
 */
object AttachmentThumbnails {
    const val COMPOSER_MAX_DIM = 256
    const val TRANSCRIPT_MAX_DIM = 2_048

    fun decodeComposer(bytes: ByteArray): ImageBitmap? = decode(bytes, COMPOSER_MAX_DIM)

    fun decodeTranscript(bytes: ByteArray): ImageBitmap? = decode(bytes, TRANSCRIPT_MAX_DIM)

    private fun decode(bytes: ByteArray, maxDim: Int): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        val sample = Integer.highestOneBit(largest / maxDim).coerceAtLeast(1)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
