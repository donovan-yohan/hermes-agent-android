package com.hermesagent.mobile.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plugin-scoped i18n: bundles are keyed by plugin, resolved active-locale →
 * the plugin's `en` → the key itself, and dropped when the plugin unloads.
 */
class PluginI18nTest {

    private fun i18n(pluginId: String, locale: String): Pair<PluginLocaleRegistry, PluginI18n> {
        val registry = PluginLocaleRegistry(activeLocale = { locale })
        val dropped = mutableListOf<() -> Unit>()
        return registry to registry.scoped(pluginId) { dispose ->
            dropped.add(dispose)
            dispose
        }
    }

    @Test
    fun `resolves the active locale, then en, then the key itself`() {
        val (_, door) = i18n("kanban", "fr")
        door.register(
            mapOf(
                "fr" to mapOf("board.title" to "Tableau"),
                "en" to mapOf("board.title" to "Board", "board.empty" to "Nothing here"),
            )
        )

        assertEquals("Tableau", door.t("board.title"))
        assertEquals("the plugin's own en bundle backs a locale it does not ship", "Nothing here", door.t("board.empty"))
        assertEquals("an unknown key is its own answer", "board.missing", door.t("board.missing"))
    }

    @Test
    fun `bundles are scoped to their plugin and cannot leak across plugins`() {
        val registry = PluginLocaleRegistry(activeLocale = { "en" })
        val register = { dispose: () -> Unit -> dispose }
        val a = registry.scoped("plugin-a", register)
        val b = registry.scoped("plugin-b", register)

        a.register(mapOf("en" to mapOf("title" to "A")))
        b.register(mapOf("en" to mapOf("title" to "B")))

        assertEquals("A", a.t("title"))
        assertEquals("B", b.t("title"))
        assertEquals("plugin-b's namespace is invisible to plugin-a", "only-b", a.t("only-b"))
    }

    @Test
    fun `the disposer drops the plugin's bundles when it unloads`() {
        val registry = PluginLocaleRegistry(activeLocale = { "en" })
        val dropped = mutableListOf<() -> Unit>()
        val door = registry.scoped("kanban") { dispose ->
            dropped.add(dispose)
            dispose
        }

        door.register(mapOf("en" to mapOf("title" to "Board")))
        assertEquals("Board", door.t("title"))

        assertEquals("register hands the disposer to the context's dispose list", 1, dropped.size)
        dropped.forEach { it() }

        assertEquals("title", door.t("title"))
    }

    @Test
    fun `re-registering merges over the previous bundle`() {
        val registry = PluginLocaleRegistry(activeLocale = { "en" })
        val door = registry.scoped("kanban") { it }

        door.register(mapOf("en" to mapOf("a" to "1", "b" to "2")))
        door.register(mapOf("en" to mapOf("b" to "changed", "c" to "3")))

        assertEquals("1", door.t("a"))
        assertEquals("changed", door.t("b"))
        assertEquals("3", door.t("c"))
    }
}
