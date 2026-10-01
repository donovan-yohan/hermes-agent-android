package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidAvatarImporterTest {
    private fun source(width: Int, height: Int): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.RED)
        for (x in width / 4 until width * 3 / 4) for (y in 0 until height) image.setPixel(x, y, Color.GREEN)
        return ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); image.recycle(); out.toByteArray() }
    }
    private fun orientedJpeg(orientation: Int): ByteArray {
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (y in 0 until 64) for (x in 0 until 64) image.setPixel(x, y, colors[(y / 32) * 2 + x / 32])
        val jpeg = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it); it.toByteArray() }
        image.recycle()
        // In-memory APP1 EXIF/TIFF; no fixture path, source URI or private metadata.
        val exif = byteArrayOf(69,120,105,102,0,0, 73,73,42,0,8,0,0,0,
            1,0, 18,1,3,0,1,0,0,0, orientation.toByte(),0,0,0, 0,0,0,0)
        val length = exif.size + 2
        return jpeg.copyOfRange(0,2) + byteArrayOf(-1,-31,(length shr 8).toByte(),length.toByte()) + exif + jpeg.copyOfRange(2,jpeg.size)
    }
    @Test fun allEightExifOrientationsTransformPixelsAndStripMetadata() = runTest {
        val importer = AndroidAvatarImporter(StandardTestDispatcher(testScheduler))
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0,1,2,3), listOf(1,0,3,2), listOf(3,2,1,0), listOf(2,3,0,1),
            listOf(0,2,1,3), listOf(2,0,3,1), listOf(3,1,2,0), listOf(1,3,0,2))
        for (orientation in 1..8) {
            val source = orientedJpeg(orientation)
            assertEquals("fixture EXIF parser", orientation, androidx.exifinterface.media.ExifInterface(ByteArrayInputStream(source))
                .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1))
            val png = requireNotNull(importer.normalize { ByteArrayInputStream(source) })
            val image = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(256, image.width); assertEquals(256, image.height)
            for (quadrant in 0..3) {
                val actual = image.getPixel(if (quadrant % 2 == 0) 64 else 192, if (quadrant < 2) 64 else 192)
                val wanted = colors[expected[orientation - 1][quadrant]]
                assertTrue("orientation=$orientation quadrant=$quadrant", kotlin.math.abs(Color.red(actual)-Color.red(wanted)) <= 10 &&
                    kotlin.math.abs(Color.green(actual)-Color.green(wanted)) <= 10 && kotlin.math.abs(Color.blue(actual)-Color.blue(wanted)) <= 10)
            }
            image.recycle()
            assertFalse(png.toString(Charsets.ISO_8859_1).contains("Exif"))
        }
    }
    private fun compressedPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            val data = java.io.DataOutputStream(output)
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(bytes.size); data.write(name); data.write(bytes)
            val crc = java.util.zip.CRC32().apply { update(name); update(bytes) }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        java.io.DataOutputStream(header).apply { writeInt(width); writeInt(height); write(byteArrayOf(8,0,0,0,0)) }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { z ->
            val row = ByteArray(width + 1)
            repeat(height) { z.write(row) }
        }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }
    @Test fun compressedPixelAndDimensionBombsAreRejectedDespiteSmallEncodedSize() = runTest {
        val importer = AndroidAvatarImporter(StandardTestDispatcher(testScheduler))
        for ((width, height) in listOf(4097 to 4096, 8193 to 1, 1 to 8193)) {
            val bytes = compressedPng(width, height)
            assertTrue(bytes.size < 100_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertEquals(width, bounds.outWidth); assertEquals(height, bounds.outHeight)
            assertNull(importer.normalize { ByteArrayInputStream(bytes) })
        }
    }
    @Test fun realImageIsCenterCroppedAndEncodedAs256SquarePng() = runTest {
        val importer = AndroidAvatarImporter(StandardTestDispatcher(testScheduler))
        val png = importer.normalize { ByteArrayInputStream(source(512, 256)) }
        assertNotNull("a decodable user image must normalize", png)
        val image = BitmapFactory.decodeByteArray(png!!, 0, png.size)
        assertEquals(256, image.width); assertEquals(256, image.height)
        assertEquals(Color.GREEN, image.getPixel(128, 128))
        assertTrue(png.size <= 2_000_000)
    }
    @Test fun refusesCorruptOversizedAndOverDimensionInputsAndClosesStream() = runTest {
        val importer = AndroidAvatarImporter(StandardTestDispatcher(testScheduler))
        var closed = false
        assertNull(importer.normalize { object : ByteArrayInputStream(byteArrayOf(1,2,3)) {
            override fun close() { closed = true; super.close() }
        } })
        assertTrue(closed)
        assertNull(importer.normalize { ByteArrayInputStream(ByteArray(15_000_001)) })
        assertNull(importer.normalize { ByteArrayInputStream(source(8193, 1)) })
    }
}
