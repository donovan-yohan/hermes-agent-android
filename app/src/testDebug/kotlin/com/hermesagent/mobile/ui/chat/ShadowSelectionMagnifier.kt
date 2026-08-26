package com.hermesagent.mobile.ui.chat

import android.os.Build
import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Silences the platform magnifier for selection journeys.
 *
 * Compose raises `android.widget.Magnifier` while a selection handle is live
 * (API 28+). Robolectric has no shadow for it and no real `Surface` behind it,
 * so the first `dismiss()` throws out of a snapshot observer and takes the test
 * with it — a host-JVM limitation, not app behaviour: the loupe is a system
 * widget the app neither configures nor asserts on.
 *
 * Deliberately narrow. Only the calls Compose's magnifier node makes are
 * stubbed, so anything else about `Magnifier` still runs its real code and a
 * future misuse would still surface.
 */
@Implements(value = Magnifier::class, minSdk = Build.VERSION_CODES.P)
class ShadowSelectionMagnifier {

    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    protected fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
        magnifierTopLeftX: Float,
        magnifierTopLeftY: Float,
    ) = Unit

    @Implementation
    protected fun update() = Unit

    @Implementation
    protected fun dismiss() = Unit
}
