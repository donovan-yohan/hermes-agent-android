package com.hermesagent.mobile.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

/** Synthetic production-Compose window draw; not emulator or physical-device evidence. */
internal fun saveNativeCapture(activity: Activity, name: String) {
    val directory = System.getenv("NATIVE_UI_CAPTURE_DIR") ?: return
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        view.draw(Canvas(bitmap))
        val output = File(directory, name)
        output.parentFile?.mkdirs()
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
