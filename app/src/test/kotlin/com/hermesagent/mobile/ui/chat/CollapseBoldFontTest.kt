package com.hermesagent.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face this app ships for the wordmark is the one Desktop draws it in.
 *
 * Desktop loads `Collapse-Bold.woff2` from `@nous-research/ui`
 * (`apps/desktop/src/styles.css:62-68` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`); the same file is in the pinned
 * checkout at `web/public/fonts/Collapse-Bold.woff2`. Android's `res/font`
 * cannot read woff2, so what ships is that file with the container removed and
 * nothing else touched. This is the gate on "and nothing else touched":
 *
 * - the bytes are pinned by digest, so a re-conversion, a re-subset or a
 *   different face swapped in under the same name fails here rather than in a
 *   screenshot;
 * - the name table still says Collapse Bold, which is what the digest means;
 * - every letter the wordmark draws is mapped, because an unmapped one renders
 *   as a blank box on device and as nothing in a capture;
 * - the advance widths are recorded, because `docs/parity/empty-states.md` and
 *   [WordmarkFitTest] argue the fit from them.
 *
 * Provenance, the conversion command and the licence line are in
 * `docs/fonts.md`.
 */
class CollapseBoldFontTest {

    /**
     * The committed artifact, byte for byte.
     *
     * Reproducible: `fontTools` 4.64.0 with `recalcTimestamp=False` writes the
     * same bytes every run — without it, `head.modified` moves and three bytes
     * of the file with it. `docs/fonts.md` carries the command.
     */
    @Test
    fun `the shipped face is the pinned Collapse Bold, by digest`() {
        assertEquals(SHA256, CollapseBoldFont.sha256)
        assertEquals(BYTES, CollapseBoldFont.bytes.size)
    }

    @Test
    fun `it is CFF-outlined and names itself Collapse Bold at weight 700`() {
        // The reason the resource is `.otf` and not `.ttf`: the outlines are
        // CFF, so the sfnt tag is OTTO. Android reads both; a name that says
        // otherwise would be the misleading part.
        assertEquals(CollapseBoldFont.CFF_SFNT, CollapseBoldFont.sfntVersion)
        assertEquals("Collapse", CollapseBoldFont.family)
        assertEquals("Bold", CollapseBoldFont.subfamily)
        // Only Bold is bundled, so only Bold may be asked for. A weight this
        // app does not ship would be synthesised, which is not Desktop's face.
        assertEquals(700, CollapseBoldFont.weightClass)
        assertEquals(1000, CollapseBoldFont.unitsPerEm)
    }

    @Test
    fun `every letter the wordmark draws is mapped`() {
        // A sanity floor first: a truncated or swapped font would otherwise
        // pass by happening to map twelve Latin capitals.
        assertTrue("only ${CollapseBoldFont.mapped.size} characters mapped", CollapseBoldFont.mapped.size > 100)
        INTRO_WORDMARK.forEach { character ->
            assertTrue(
                "'$character' (U+%04X) is not in the shipped Collapse Bold".format(character.code),
                character in CollapseBoldFont.mapped,
            )
        }
    }

    /**
     * The numbers `docs/parity/empty-states.md` builds its fit table on, read
     * off the shipped file rather than asserted from a screenshot.
     */
    @Test
    fun `the wordmark's runs are the ems the fit table uses`() {
        assertEquals(HERMES_EM, CollapseBoldFont.emRun("HERMES"), 0.001f)
        assertEquals(AGENT_EM, CollapseBoldFont.emRun("AGENT"), 0.001f)
        assertTrue("HERMES must be the wider line, or the fit measures the wrong one", HERMES_EM > AGENT_EM)
    }

    /**
     * The cross-check that ties the shipped file to the pinned Desktop capture.
     *
     * `docs/parity/visual/empty-states/empty-chat-intro-light/desktop/contract.json`
     * node 4 measures `HERMES AGENT` at `1052.0px` in a `135.637px` face —
     * `7.756 em` — with `.wordmark`'s `0.08em` tracking applied to all twelve
     * characters. The shipped face's own advances plus that tracking must land
     * on the same number, or the file in `res/font` is not the file Desktop
     * rendered.
     */
    @Test
    fun `the shipped advances reproduce the pinned Desktop capture`() {
        val tracked = CollapseBoldFont.emRun(INTRO_WORDMARK) + INTRO_WORDMARK.length * WORDMARK_TRACKING_EM
        assertEquals(DESKTOP_RUN_PX / DESKTOP_FONT_PX, tracked, 0.02f)
    }

    companion object {
        const val SHA256 = "c0cbb0b86bcfcf7ba5103470944925d1eaaa4576d8fbd068f263cf540fc9821d"
        const val BYTES = 117_164

        /** Glyph advances only. Tracking is the type scale's, and is added on top. */
        const val HERMES_EM = 3.727f
        const val AGENT_EM = 2.861f

        /** `.wordmark`'s `letter-spacing: 0.08em` (`styles.css:1634` @ `3ca096de`). */
        const val WORDMARK_TRACKING_EM = 0.08f

        /** `contract.json` node 4: the visible fitted span, and the face it was set in. */
        const val DESKTOP_RUN_PX = 1052.0f
        const val DESKTOP_FONT_PX = 135.637f
    }
}
