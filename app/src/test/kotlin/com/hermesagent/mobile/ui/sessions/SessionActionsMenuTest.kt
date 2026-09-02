package com.hermesagent.mobile.ui.sessions

import com.hermesagent.mobile.ui.common.HermesIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The per-session actions menu as a pure item spec.
 *
 * Desktop's group order is the part later slices can break silently: rename,
 * delete, pin and archive all land in slots this menu already reserves, so the
 * order and the separator placement are pinned here rather than in a rendered
 * assertion. Every expectation is transcribed from
 * `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, not derived from production code.
 */
class SessionActionsMenuTest {

    @Test
    fun `groups keep Desktop's fixed open-identity-work-tab-danger order`() {
        // session-actions-menu.tsx:234 (openItems), :291 (identityItems),
        // :344 (workItems), :371 (tabItems), :433 (dangerItems).
        assertEquals(
            listOf("Open", "Identity", "Work", "Tab", "Danger"),
            SessionActionsGroup.entries.map { it.name },
        )
    }

    @Test
    fun `a populated menu separates every adjacent group exactly once`() {
        val perGroup = SessionActionsGroup.entries.map { group ->
            SessionActionItem(group, HermesIcon.Copy, "${group.name} verb")
        }

        assertEquals(
            listOf(
                SessionMenuNode.Action(perGroup[0]),
                SessionMenuNode.Separator,
                SessionMenuNode.Action(perGroup[1]),
                SessionMenuNode.Separator,
                SessionMenuNode.Action(perGroup[2]),
                SessionMenuNode.Separator,
                SessionMenuNode.Action(perGroup[3]),
                SessionMenuNode.Separator,
                SessionMenuNode.Action(perGroup[4]),
            ),
            sessionActionsMenuPlan(perGroup),
        )
    }

    @Test
    fun `the Desktop row menu renders three rules between its four populated groups`() {
        // Desktop's row surface leaves tabItems empty (:371), so renderItems
        // (:465-522) paints exactly three rules: after open, after identity,
        // and before danger.
        val plan = sessionActionsMenuPlan(DESKTOP_ROW_MENU)

        assertEquals(3, plan.count { it is SessionMenuNode.Separator })
        assertEquals(
            listOf(3, 4, 2, 2),
            plan.split().map { it.size },
        )
        assertEquals(
            listOf(
                SessionActionsGroup.Open,
                SessionActionsGroup.Identity,
                SessionActionsGroup.Work,
                SessionActionsGroup.Danger,
            ),
            plan.split().map { group -> group.first().group },
        )
    }

    @Test
    fun `an unpopulated group takes no rule with it`() {
        // The shell state: one identity verb, four empty slots, no leading,
        // trailing or doubled rule anywhere.
        assertEquals(
            listOf(SessionMenuNode.Action(COPY_ID_ITEM)),
            sessionActionsMenuPlan(listOf(COPY_ID_ITEM)),
        )
        assertEquals(emptyList<SessionMenuNode>(), sessionActionsMenuPlan(emptyList()))
    }

    @Test
    fun `the plan restores Desktop's group order whatever order verbs arrive in`() {
        val scrambled = DESKTOP_ROW_MENU.reversed()
        val plan = sessionActionsMenuPlan(scrambled)

        // Groups come back in Desktop's order …
        assertEquals(
            sessionActionsMenuPlan(DESKTOP_ROW_MENU).split().map { it.first().group },
            plan.split().map { it.first().group },
        )
        // … and within a group the caller's own order survives, so a later
        // slice controls where its verb sits among its neighbours.
        assertEquals(
            listOf("Copy ID", "Mark as unread", "Pin", "Rename…"),
            plan.split()[1].map { it.label },
        )
    }

    @Test
    fun `only Delete is destructive-red`() {
        // Archive shares the danger group without the variant
        // (session-actions-menu.tsx:435-461).
        assertEquals(
            listOf("Delete"),
            DESKTOP_ROW_MENU.filter { it.destructive }.map { it.label },
        )
    }

    @Test
    fun `the ported verbs keep Desktop's codicon vocabulary`() {
        // session-actions-menu.tsx:292,304,317,345,357,435,444 plus the kebab
        // trigger at session-row.tsx:326. Code points are Codicons 0.0.45;
        // HermesIconFontTest proves each one resolves in the shipped font.
        val expected = mapOf(
            "kebab-vertical" to (HermesIcon.KebabVertical to 0xEB10),
            "edit" to (HermesIcon.Edit to 0xEA73),
            "pin" to (HermesIcon.Pin to 0xEB2B),
            "mail" to (HermesIcon.Mail to 0xEB1C),
            "mail-read" to (HermesIcon.MailRead to 0xEB1B),
            "copy" to (HermesIcon.Copy to 0xEBCC),
            "repo-forked" to (HermesIcon.RepoForked to 0xEA63),
            "cloud-download" to (HermesIcon.CloudDownload to 0xEAC2),
            "folder" to (HermesIcon.Folder to 0xEA83),
            "archive" to (HermesIcon.Archive to 0xEA98),
            "trash" to (HermesIcon.Trash to 0xEA81),
            // Not Desktop's — Desktop's copy failure draws lucide `X`
            // (copy-button.tsx:9,142). Android has one glyph family, so the
            // failure state uses codicon `close`, the same pair-mate this app
            // already uses wherever a lucide X appears upstream.
            "close" to (HermesIcon.Close to 0xEA76),
        )

        expected.forEach { (codicon, mapping) ->
            val (icon, codePoint) = mapping
            assertEquals(codicon, 1, icon.glyph.length)
            assertEquals(codicon, codePoint, icon.glyph.single().code)
        }
    }

    @Test
    fun `this build offers only the verbs it can actually perform`() {
        assertEquals(listOf("Rename…", "Copy ID", "Delete"), sessionActionItems("s-1").map { it.label })
        assertEquals(
            listOf(
                SessionActionsGroup.Identity,
                SessionActionsGroup.Identity,
                SessionActionsGroup.Danger,
            ),
            sessionActionItems("s-1").map { it.group },
        )
    }

    @Test
    fun `a session with no id has nothing to offer`() {
        assertEquals(emptyList<SessionActionItem>(), sessionActionItems(""))
        assertEquals(emptyList<SessionActionItem>(), sessionActionItems("  "))
    }

    @Test
    fun `the copy verb confirms in place rather than raising a notice`() {
        val idle = sessionActionItems("s-1")[1]
        val done = sessionActionItems("s-1", SessionIdCopyStatus.Copied)[1]

        assertEquals("Copy ID", idle.label)
        assertEquals(HermesIcon.Copy, idle.icon)
        // Desktop's CopyButton swaps in t.common.copied verbatim
        // (copy-button.tsx:147-148; en.ts:21).
        assertEquals("Copied", done.label)
        assertEquals(HermesIcon.Check, done.icon)
        // The confirmation must not move the item out of its slot.
        assertEquals(idle.group, done.group)
    }

    @Test
    fun `a refused clip says so in the item's own slot`() {
        val failed = sessionActionItems("s-1", SessionIdCopyStatus.Failed)[1]

        // en.ts:2166, the message Desktop attaches to this exact item
        // (session-actions-menu.tsx:482).
        assertEquals("Could not copy session ID", failed.label)
        // Desktop's error icon is an X (copy-button.tsx:142).
        assertEquals(HermesIcon.Close, failed.icon)
        assertEquals(SessionActionsGroup.Identity, failed.group)
        // Desktop tints nothing on failure — the row is given `text-current`
        // and no variant (session-actions-menu.tsx:483). Destructive-red is
        // Delete's alone, and a transient failure must not borrow it.
        assertFalse(failed.destructive)
    }

    @Test
    fun `every copy state keeps the menu structure`() {
        // Whatever the clipboard did, the menu keeps its shape: the item must
        // not appear twice, vanish, or move group under a confirmation.
        SessionIdCopyStatus.entries.forEach { status ->
            val items = sessionActionItems("s-1", status)
            assertEquals(status.name, 3, items.size)
            assertEquals(status.name, SessionActionsGroup.Identity, items[1].group)
            assertEquals(status.name, emptyList<SessionActionItem>(), sessionActionItems("", status))
        }
    }

    /** Split a plan back into its groups on the separators. */
    private fun List<SessionMenuNode>.split(): List<List<SessionActionItem>> {
        val groups = mutableListOf<List<SessionActionItem>>()
        var current = mutableListOf<SessionActionItem>()
        forEach { node ->
            when (node) {
                is SessionMenuNode.Separator -> {
                    groups += current
                    current = mutableListOf()
                }

                is SessionMenuNode.Action -> current += node.item
            }
        }
        groups += current
        return groups
    }

    private companion object {
        val COPY_ID_ITEM = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Copy, "Copy ID")

        /** Stands in for a Desktop glyph this app has no reason to port. */
        val NO_ANDROID_GLYPH = HermesIcon.Ellipsis

        /**
         * Desktop's row-surface menu with every handler present: the four item
         * arrays at :234, :291, :344 and :433, plus the Copy ID row rendered
         * inside the identity group at :479-488. Labels are `i18n/en.ts:2151-2167`.
         *
         * Deliberately hand-transcribed. Android will not ship the open group
         * — no tabs, no windows, no local terminal — so those rows carry
         * [NO_ANDROID_GLYPH] rather than a fabricated mapping; only their
         * *group* is asserted.
         */
        val DESKTOP_ROW_MENU = listOf(
            SessionActionItem(SessionActionsGroup.Open, NO_ANDROID_GLYPH, "Open in new tab"),
            SessionActionItem(SessionActionsGroup.Open, NO_ANDROID_GLYPH, "New window"),
            SessionActionItem(SessionActionsGroup.Open, NO_ANDROID_GLYPH, "Open in terminal"),
            SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Edit, "Rename…"),
            SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Pin, "Pin"),
            SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Mail, "Mark as unread"),
            COPY_ID_ITEM,
            SessionActionItem(SessionActionsGroup.Work, HermesIcon.RepoForked, "Branch"),
            SessionActionItem(SessionActionsGroup.Work, HermesIcon.CloudDownload, "Export"),
            SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Archive, "Archive"),
            SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Trash, "Delete", destructive = true),
        )
    }
}
