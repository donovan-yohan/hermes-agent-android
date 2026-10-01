package com.hermesagent.mobile.plugins.bots

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class BotsAvatarShapeTest {
    @Test
    fun `bot avatar uses Desktop twenty two percent corners rather than a circle`() {
        val outline = BOT_AVATAR_SHAPE.createOutline(Size(100f, 100f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(22f, outline.roundRect.topLeftCornerRadius.x, 0.001f)
        assertEquals(22f, outline.roundRect.topLeftCornerRadius.y, 0.001f)
    }
}
