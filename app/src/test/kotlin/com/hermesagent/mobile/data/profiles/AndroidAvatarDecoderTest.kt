package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun syntheticAvatar(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.MAGENTA)
    bitmap.setPixel(0, 0, Color.GREEN)
    return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(format, 95, output))
        bitmap.recycle()
        output.toByteArray()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidAvatarDecoderTest {
    @Test fun realPngJpegWebpDecodeRectanglesAndPixels() = runTest {
        val decoder = AndroidAvatarDecoder(StandardTestDispatcher(testScheduler))
        @Suppress("DEPRECATION")
        val formats = listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)
        for (format in formats) {
            val bytes = syntheticAvatar(32, 16, format)
            val mime = when (format) {
                Bitmap.CompressFormat.PNG -> "image/png"
                Bitmap.CompressFormat.JPEG -> "image/jpeg"
                else -> "image/webp"
            }
            val payload = parseAvatarPayload(avatarWire(bytes, mime)) as AvatarPayload.Raster
            val image = decoder.decode(payload.bytes)!!
            assertEquals(32, image.width)
            assertEquals(16, image.height)
            assertTrue(isBoundedAvatar(image))
            if (format == Bitmap.CompressFormat.PNG) {
                assertEquals(Color.GREEN, image.getPixel(0, 0))
                assertEquals(Color.MAGENTA, image.getPixel(10, 10))
            }
        }
    }

    @Test fun ceilSamplingAroundNonPowerOfTwoEdges() = runTest {
        val decoder = AndroidAvatarDecoder(StandardTestDispatcher(testScheduler))
        for (edge in listOf(256, 257, 511, 512, 513, 8192)) {
            val image = decoder.decode(syntheticAvatar(edge, 17))!!
            assertTrue("edge=$edge actual=${image.width}", image.width <= 256)
            assertTrue(image.height in 1..256)
            assertTrue(image.allocationByteCount <= AvatarLimits.IMAGE_BYTES)
        }
    }

    @Test fun dimensionsAndLongPixelArithmeticBeforeAllocation() = runTest {
        assertEquals(32, avatarSampleSize(8192, 2048))
        assertEquals(16, avatarSampleSize(4096, 4096))
        for ((w, h) in listOf(8193 to 1, 1 to 8193, 4097 to 4096, 8192 to 8192,
            0 to 1, -1 to 2, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertNull(avatarSampleSize(w, h))
        }
        val decoder = AndroidAvatarDecoder(StandardTestDispatcher(testScheduler))
        // Real raster bounds are rejected, not just the pure arithmetic helper.
        assertNull(decoder.decode(syntheticAvatar(8193, 1)))
        assertNull(decoder.decode(syntheticAvatar(4097, 4096)))
    }

    @Test fun signatureIsNotProofOfDecodableRaster() = runTest {
        val decoder = AndroidAvatarDecoder(StandardTestDispatcher(testScheduler))
        assertNull(decoder.decode(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        assertNull(decoder.decode(ByteArray(0)))
        assertNull(decoder.decode(ByteArray(AvatarLimits.BYTES + 1)))
        assertNull(decoder.decode(syntheticAvatar(16, 8).copyOf(40)))
    }
}
