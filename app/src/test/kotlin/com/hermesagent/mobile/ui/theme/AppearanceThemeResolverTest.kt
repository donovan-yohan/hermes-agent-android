package com.hermesagent.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppearanceThemeResolverTest {
    @Test fun `retired persisted names use Nous even with stale registered definitions`() {
        for (name in listOf("default", "gold", "nous-light")) {
            val stale = BuiltinThemes.Mono.copy(name = name)
            assertSame(BuiltinThemes.Nous, resolveAppearancePreset(name, listOf(stale)))
            assertSame(BuiltinThemes.Nous, resolveAppearancePreset(name, emptyList()))
        }
    }

    @Test fun `unknown stored custom pick resolves when its scoped definition arrives`() {
        val saved = AppearanceSelection(themeName = "saved-skin", mode = HermesThemeMode.Dark)
        val custom = BuiltinThemes.Mono.copy(name = saved.themeName)
        assertSame(BuiltinThemes.Nous, resolveAppearancePreset(saved.themeName, emptyList()))
        assertSame(custom, resolveAppearancePreset(saved.themeName, listOf(custom)))
        assertEquals("saved-skin", saved.themeName)
        assertEquals(HermesThemeMode.Dark, saved.mode)
        assertSame(BuiltinThemes.Nous, resolveAppearancePreset(saved.themeName, emptyList()))
    }

    @Test fun `builtins win and slash-only aliases are not retired appearance names`() {
        assertSame(BuiltinThemes.Nous, resolveAppearancePreset("nous", listOf(BuiltinThemes.Mono.copy(name = "nous"))))
        for (name in listOf("hermes", "ares")) {
            val custom = BuiltinThemes.Mono.copy(name = name)
            assertSame(custom, resolveAppearancePreset(name, listOf(custom)))
        }
    }
}
