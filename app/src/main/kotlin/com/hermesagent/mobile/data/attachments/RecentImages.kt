package com.hermesagent.mobile.data.attachments

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** How much of this device's image library the rail may read right now. */
enum class RecentImageAccess {
    /** Not checked on this device yet. The rail shows nothing and asks for nothing. */
    Unknown,
    /** The device granted the full media permission for the running platform level. */
    Granted,
    /** Android 14+ partial access: only the images the person selected in the OS dialog. */
    Partial,
    /** Not granted. The rail cannot read the library; Choose photos still works. */
    Denied,
    ;

    /** True when a media grant exists, full or partial, so the rail may be read. */
    val readsLibrary: Boolean get() = this == Granted || this == Partial
}

/**
 * One device image the rail may offer. Deliberately carries no Uri and no path:
 * [id] resolves to a source at tap time, in memory, once.
 */
data class RecentImage(val id: Long, val displayName: String, val mimeType: String? = null)

/** Bounds for the rail, so one sheet open cannot read an unbounded library. */
object RecentImagesPolicy {
    const val MAX_RAIL_IMAGES = 12
    const val THUMBNAIL_MAX_DIM = 192
}

/** Reads this device's newest images and their bounded previews. */
interface RecentImagesSource {
    /** At most [limit] rows, newest first. May throw; the caller owns the failure. */
    fun recentImages(limit: Int): List<RecentImage>
    /** A bounded system-decoded preview, or null when that image cannot be read. */
    fun thumbnail(imageId: Long, maxPx: Int): ImageBitmap?
    /** The in-memory source string for one row. Never stored, never sent as a path. */
    fun sourceFor(image: RecentImage): String
}

/** The platform MediaStore implementation for the add sheet's device-local rail. */
class MediaStoreRecentImages(private val resolver: ContentResolver) : RecentImagesSource {
    override fun recentImages(limit: Int): List<RecentImage> {
        val bounded = limit.coerceIn(1, RecentImagesPolicy.MAX_RAIL_IMAGES)
        val cursor = resolver.query(
            collection(),
            PROJECTION,
            "${MediaStore.Images.Media.MIME_TYPE} LIKE ?",
            arrayOf("image/%"),
            sortOrder(bounded),
        ) ?: return emptyList()
        return cursor.use { rows(it, bounded) }
    }

    override fun thumbnail(imageId: Long, maxPx: Int): ImageBitmap? = runCatching {
        val bounded = maxPx.coerceIn(1, RecentImagesPolicy.THUMBNAIL_MAX_DIM)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(itemUri(imageId), Size(bounded, bounded), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                imageId,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
        bitmap?.boundedTo(bounded)?.asImageBitmap()
    }.getOrNull()

    override fun sourceFor(image: RecentImage): String = itemUri(image.id).toString()

    private fun Bitmap.boundedTo(maxPx: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxPx) return this
        val scale = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        internal val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )

        /** Newest first, with the bound carried in the query itself. */
        internal fun sortOrder(limit: Int): String =
            "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit"

        internal fun collection(): Uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        internal fun itemUri(imageId: Long): Uri = ContentUris.withAppendedId(collection(), imageId)

        /**
         * Maps rows fail-closed: a row with no id or display-name column is skipped,
         * and a provider that ignores LIMIT cannot over-read past [limit]. A blank
         * display name uses the attachment fallback so the rail remains tappable.
         */
        internal fun rows(cursor: Cursor, limit: Int): List<RecentImage> {
            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return emptyList()
            val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val bounded = limit.coerceIn(0, RecentImagesPolicy.MAX_RAIL_IMAGES)
            val result = ArrayList<RecentImage>(bounded)
            var scanned = 0
            while (scanned < bounded && cursor.moveToNext()) {
                scanned += 1
                if (cursor.isNull(idIndex) || cursor.isNull(nameIndex)) continue
                result += RecentImage(
                    id = cursor.getLong(idIndex),
                    displayName = AttachmentPolicy.sanitizeDisplayName(cursor.getString(nameIndex)),
                    mimeType = mimeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                )
            }
            return result
        }
    }
}
