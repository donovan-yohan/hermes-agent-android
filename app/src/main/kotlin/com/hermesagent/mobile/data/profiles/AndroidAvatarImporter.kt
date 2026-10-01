package com.hermesagent.mobile.data.profiles

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Original input is memory-only. Decode first frame; never fall back to uploading undecoded input. */
internal class AndroidAvatarImporter(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun normalize(open: () -> InputStream?): ByteArray? = withContext(dispatcher) {
        permits.withPermit {
            try {
                ensureActive()
                val bytes = open()?.use { stream ->
                    ByteArrayOutputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0 || out.size() + count > SOURCE_BYTES) return@withPermit null
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                } ?: return@withPermit null
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) return@withPermit null
                    // Reuse the read policy's long pixel arithmetic before native allocation.
                    val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight) ?: return@withPermit null
                    ensureActive()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
                    }) ?: return@withPermit null
                    try {
                        if (!isBoundedAvatar(bitmap)) return@withPermit null
                        val orientation = if (bounds.outMimeType == "image/jpeg") try {
                            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        } catch (_: Exception) { 1 } else 1
                        val square = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                        try {
                            ensureActive()
                            val canvas = Canvas(square)
                            // Transform around centre: all eight EXIF orientations, then centre crop.
                            canvas.translate(128f, 128f)
                            when (orientation) {
                                2 -> canvas.scale(-1f, 1f)
                                3 -> canvas.rotate(180f)
                                4 -> canvas.scale(1f, -1f)
                                5 -> { canvas.rotate(90f); canvas.scale(1f, -1f) }
                                6 -> canvas.rotate(90f)
                                7 -> { canvas.rotate(90f); canvas.scale(-1f, 1f) }
                                8 -> canvas.rotate(270f)
                            }
                            val side = minOf(bitmap.width, bitmap.height)
                            val left = (bitmap.width - side) / 2; val top = (bitmap.height - side) / 2
                            canvas.drawBitmap(bitmap, Rect(left, top, left + side, top + side),
                                RectF(-128f, -128f, 128f, 128f), Paint(Paint.FILTER_BITMAP_FLAG))
                            ByteArrayOutputStream().use { output ->
                                if (!square.compress(Bitmap.CompressFormat.PNG, 100, output)) return@withPermit null
                                ensureActive()
                                if (output.size() !in 1..AvatarLimits.BYTES) null else output.toByteArray()
                            }
                        } finally { square.recycle() }
                    } finally { bitmap.recycle() }
                } finally { bytes.fill(0) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    private companion object {
        const val SOURCE_BYTES = 15_000_000
        val permits = Semaphore(1)
    }
}
