package com.hermesagent.mobile.data.attachments

import android.Manifest
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentImagesTest {

    @Test
    fun `rows map metadata, sanitize blank names, and stop at the bound`() {
        val cursor = MatrixCursor(MediaStoreRecentImages.PROJECTION).apply {
            addRow(arrayOf<Any?>(4L, " camera image ", "image/jpeg"))
            addRow(arrayOf<Any?>(5L, " ", "image/png"))
            addRow(arrayOf<Any?>(6L, "later.jpg", "image/jpeg"))
        }

        val rows = MediaStoreRecentImages.rows(cursor, 2)

        assertEquals(
            listOf(
                RecentImage(4L, "camera image", "image/jpeg"),
                RecentImage(5L, "attachment", "image/png"),
            ),
            rows,
        )
    }

    @Test
    fun `rows skip records without an id or name`() {
        val cursor = MatrixCursor(MediaStoreRecentImages.PROJECTION).apply {
            addRow(arrayOf<Any?>(null, "image.jpg", "image/jpeg"))
            addRow(arrayOf<Any?>(3L, null, "image/jpeg"))
            addRow(arrayOf<Any?>(4L, "kept.jpg", "image/jpeg"))
        }

        assertEquals(listOf(RecentImage(4L, "kept.jpg", "image/jpeg")), MediaStoreRecentImages.rows(cursor, 12))
    }

    @Test
    fun `rows never inspect past the bound when invalid records come first`() {
        val cursor = MatrixCursor(MediaStoreRecentImages.PROJECTION).apply {
            addRow(arrayOf<Any?>(null, "missing-id.jpg", "image/jpeg"))
            addRow(arrayOf<Any?>(2L, null, "image/jpeg"))
            addRow(arrayOf<Any?>(3L, "outside-bound.jpg", "image/jpeg"))
        }

        assertEquals(emptyList<RecentImage>(), MediaStoreRecentImages.rows(cursor, 2))
    }

    @Test
    fun `rows fail closed when the id or name column is absent`() {
        val noName = MatrixCursor(arrayOf(MediaStore.Images.Media._ID)).apply { addRow(arrayOf<Any?>(3L)) }
        val noId = MatrixCursor(arrayOf(MediaStore.Images.Media.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>("image.jpg")) }

        assertEquals(emptyList<RecentImage>(), MediaStoreRecentImages.rows(noName, 1))
        assertEquals(emptyList<RecentImage>(), MediaStoreRecentImages.rows(noId, 1))
    }

    @Test
    fun `rows tolerate an absent mime column`() {
        val cursor = MatrixCursor(
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
        ).apply { addRow(arrayOf<Any?>(3L, "image.jpg")) }

        assertEquals(listOf(RecentImage(3L, "image.jpg")), MediaStoreRecentImages.rows(cursor, 1))
    }

    @Test
    fun `sort order is newest first and bounded`() {
        val order = MediaStoreRecentImages.sortOrder(7)

        assertTrue(order.contains("${MediaStore.Images.Media.DATE_ADDED} DESC"))
        assertTrue(order.contains("LIMIT 7"))
    }

    @Test
    fun `null resolver result becomes no images`() {
        val source = MediaStoreRecentImages(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

        assertEquals(emptyList<RecentImage>(), source.recentImages(RecentImagesPolicy.MAX_RAIL_IMAGES))
    }

    @Test
    fun `requested permissions match their platform media model`() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), RecentImagePermissions.requested(26))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), RecentImagePermissions.requested(33))
        val expectedModern = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        assertArrayEquals(expectedModern, RecentImagePermissions.requested(34))
        assertArrayEquals(expectedModern, RecentImagePermissions.requested(35))
    }

    @Test
    fun `access distinguishes full partial and denied grants`() {
        assertEquals(
            RecentImageAccess.Granted,
            RecentImagePermissions.access(26) { it == Manifest.permission.READ_EXTERNAL_STORAGE },
        )
        assertEquals(
            RecentImageAccess.Granted,
            RecentImagePermissions.access(33) { it == Manifest.permission.READ_MEDIA_IMAGES },
        )
        assertEquals(
            RecentImageAccess.Partial,
            RecentImagePermissions.access(34) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED },
        )
        assertEquals(
            RecentImageAccess.Granted,
            RecentImagePermissions.access(34) {
                it == Manifest.permission.READ_MEDIA_IMAGES ||
                    it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            },
        )
        assertEquals(RecentImageAccess.Denied, RecentImagePermissions.access(35) { false })
    }

    @Test
    fun `scoped fence accepts a current result once and refuses a moved world`() {
        var generation = 8L
        var session: String? = "session-a"
        val fence = PickerGenerationFence { AttachmentPickScope(generation, session) }

        assertNull(fence.accept())
        fence.begin()
        assertEquals(AttachmentPickScope(8L, "session-a"), fence.accept())
        assertNull(fence.accept())

        fence.begin()
        generation = 9L
        assertNull(fence.accept())

        fence.begin()
        session = "session-b"
        assertNull(fence.accept())

        fence.begin()
        fence.invalidate()
        assertNull(fence.accept())
    }

    @Test
    fun `a launched pick stops holding once its session changes`() {
        var generation = 4L
        var session: String? = "session-a"
        val fence = PickerGenerationFence { AttachmentPickScope(generation, session) }
        fence.begin()
        val scope = checkNotNull(fence.accept())

        assertTrue(fence.holds(scope))
        session = "session-b"
        assertFalse(fence.holds(scope))
        assertEquals(scope, AttachmentPickScope(4L, "session-a"))
    }

    @Test
    fun `a refused library read reports itself rather than an empty device`() = runTest {
        val failing = FakeRecentImagesSource(failRows = true)

        val read = failing.readRecentImages()

        assertTrue(read.failed)
        assertTrue(read.images.isEmpty())
        assertTrue(read.thumbnails.isEmpty())
    }

    @Test
    fun `a rail read bounds its rows and survives one undecodable preview`() = runTest {
        val source = FakeRecentImagesSource(
            rows = (1L..20L).map { RecentImage(it, "image-$it.png") },
            failPreviews = true,
        )

        val read = source.readRecentImages()

        assertFalse(read.failed)
        assertEquals(RecentImagesPolicy.MAX_RAIL_IMAGES, read.images.size)
        assertTrue(read.thumbnails.isEmpty())
    }

    private class FakeRecentImagesSource(
        private val rows: List<RecentImage> = emptyList(),
        private val failRows: Boolean = false,
        private val failPreviews: Boolean = false,
    ) : RecentImagesSource {
        override fun recentImages(limit: Int): List<RecentImage> {
            if (failRows) error("fixture library failure")
            return rows.take(limit.coerceAtMost(RecentImagesPolicy.MAX_RAIL_IMAGES))
        }

        override fun thumbnail(imageId: Long, maxPx: Int): ImageBitmap? {
            if (failPreviews) error("fixture preview failure")
            return null
        }

        override fun sourceFor(image: RecentImage): String = "content://fixture/media/${image.id}"
    }
}
