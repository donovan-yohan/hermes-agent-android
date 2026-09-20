package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface AvatarDecoder {
    suspend fun decode(bytes: ByteArray): Bitmap?
}

/** Software, static first-frame raster only. EXIF orientation is deliberately not applied. */
internal class AndroidAvatarDecoder(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AvatarDecoder {
    override suspend fun decode(bytes: ByteArray): Bitmap? = withContext(dispatcher) {
        ensureActive()
        if (bytes.isEmpty() || bytes.size > AvatarLimits.BYTES) return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight) ?: return@withContext null
            ensureActive()
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
            // This bitmap has never escaped; recycling here cannot invalidate a UI reference.
            try {
                ensureActive()
                if (!isBoundedAvatar(image)) {
                    image.recycle()
                    return@withContext null
                }
                image
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                image.recycle()
                throw cancelled
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal fun avatarSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width > AvatarLimits.SOURCE_EDGE || height > AvatarLimits.SOURCE_EDGE ||
        width.toLong() * height > AvatarLimits.SOURCE_PIXELS
    ) return null
    var sample = 1
    // Ceil division bounds decoders that round the resulting edge upwards.
    while ((width + sample - 1) / sample > AvatarLimits.IMAGE_EDGE ||
        (height + sample - 1) / sample > AvatarLimits.IMAGE_EDGE
    ) sample *= 2
    return sample
}

internal fun isBoundedAvatar(image: Bitmap): Boolean = !image.isRecycled &&
    image.width in 1..AvatarLimits.IMAGE_EDGE && image.height in 1..AvatarLimits.IMAGE_EDGE &&
    image.allocationByteCount in 1..AvatarLimits.IMAGE_BYTES
