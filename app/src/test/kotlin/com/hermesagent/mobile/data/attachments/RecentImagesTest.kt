package com.hermesagent.mobile.data.attachments

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
    fun `granted library read succeeds through a strict provider with a separate limit`() = runTest {
        StrictImagesProvider().use { provider ->
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
            val source = MediaStoreRecentImages(resolver)

            val read = source.readRecentImages()

            assertFalse("Valid media access must not become a failed rail query", read.failed)
            assertEquals((20L downTo 9L).toList(), read.images.map { it.id })
            assertEquals(RecentImagesPolicy.MAX_RAIL_IMAGES, provider.queryArgs!!.getInt(ContentResolver.QUERY_ARG_LIMIT))
            assertTrue(provider.lastCursor!!.isClosed)
            assertEquals("content://media/external/images/media/20", source.sourceFor(read.images.first()))
        }
    }

    @Test
    @Config(sdk = [26, 34])
    fun `provider query clamps its requested limit`() {
        StrictImagesProvider().use { provider ->
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
            val source = MediaStoreRecentImages(resolver)

            for ((requested, expected) in listOf(-1 to 1, 0 to 1, 7 to 7, 100 to 12)) {
                assertEquals(expected, source.recentImages(requested).size)
                assertEquals(expected, provider.queryArgs!!.getInt(ContentResolver.QUERY_ARG_LIMIT))
            }
        }
    }

    @Test
    fun `provider that ignores query limit is still bounded and closed`() {
        StrictImagesProvider(honorLimit = false).use { provider ->
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

            val images = MediaStoreRecentImages(resolver).recentImages(7)

            assertEquals((20L downTo 14L).toList(), images.map { it.id })
            assertTrue(provider.lastCursor!!.isClosed)
        }
    }

    /** Exercises Android's SQL grammar validator, not just a fake source's rows. */
    private class StrictImagesProvider(private val honorLimit: Boolean = true) : ContentProvider(), AutoCloseable {
        private val database = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE images (_id INTEGER, _display_name TEXT, mime_type TEXT, date_added INTEGER)")
            for (id in 1..20) {
                execSQL("INSERT INTO images VALUES (?, ?, ?, ?)", arrayOf<Any>(id, "image-$id.jpg", "image/jpeg", id))
            }
            execSQL("INSERT INTO images VALUES (21, 'not-an-image', 'video/mp4', 21)")
        }
        var queryArgs: Bundle? = null
        var lastCursor: Cursor? = null

        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
            this.queryArgs = queryArgs
            return queryRows(
                projection,
                queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
                queryArgs?.takeIf { honorLimit && it.containsKey(ContentResolver.QUERY_ARG_LIMIT) }
                    ?.getInt(ContentResolver.QUERY_ARG_LIMIT)?.toString(),
            )
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            queryRows(projection, selection, selectionArgs, sortOrder, null)

        private fun queryRows(projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, limit: String?): Cursor {
            val builder = SQLiteQueryBuilder().apply {
                tables = "images"
                setProjectionMap((MediaStoreRecentImages.PROJECTION + MediaStore.Images.Media.DATE_ADDED).associateWith { it })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setStrictGrammar(true)
            }
            return builder.query(database, projection, selection, selectionArgs, null, null, sortOrder, limit)
                .also { lastCursor = it }
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "vnd.android.cursor.dir/image"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unused")
        override fun close() = database.close()
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
