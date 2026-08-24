package com.hermesagent.mobile.ui.sessions

import com.hermesagent.mobile.data.session.SessionStatus
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop parity for `showsRunningArc` in
 * `apps/desktop/src/store/session-dot-state.ts:70-80` @
 * `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`.
 */
class SessionRunningOutlineTest {

    @Test
    fun `working and stalled sessions keep the running outline`() {
        assertTrue(SessionStatus.Working.showsRunningOutline())
        assertTrue(SessionStatus.Stalled.showsRunningOutline())
    }

    @Test
    fun `waiting background and settled sessions have no running outline`() {
        SessionStatus.entries
            .filterNot { it == SessionStatus.Working || it == SessionStatus.Stalled }
            .forEach { status -> assertFalse("$status must not show the running outline", status.showsRunningOutline()) }
    }

    @Test
    fun `gradient line length projects the exact 300 percent sidebar layer`() {
        // Desktop's 300% pseudo-element for a 300x48 host is 900x144. The
        // line length is the CSS gradient-direction projection, not max side.
        assertEquals(
            443.13388f,
            sessionRunningOutlineGradientLength(
                layerWidth = 900f,
                layerHeight = 144f,
                direction = Offset(0.34202015f, 0.9396926f),
            ),
            0.001f,
        )
    }
}
