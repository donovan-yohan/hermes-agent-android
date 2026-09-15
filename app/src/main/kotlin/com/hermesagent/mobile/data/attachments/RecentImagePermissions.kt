package com.hermesagent.mobile.data.attachments

import android.Manifest
import android.os.Build

/**
 * The runtime media permission the rail reads behind, per platform level, and
 * what the granted set means. Android 13 split image access out of storage;
 * Android 14 added the partial selected-photos grant.
 */
object RecentImagePermissions {
    /** Never asks for a permission the running platform level does not define. */
    fun requested(sdkInt: Int): Array<String> = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(sdkInt: Int, granted: (String) -> Boolean): RecentImageAccess = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> RecentImageAccess.Granted
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> RecentImageAccess.Partial
        sdkInt < Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> RecentImageAccess.Granted
        else -> RecentImageAccess.Denied
    }
}
