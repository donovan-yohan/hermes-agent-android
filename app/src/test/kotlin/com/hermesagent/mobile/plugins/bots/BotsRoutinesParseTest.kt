package com.hermesagent.mobile.plugins.bots

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routines parser and the derivation the surface renders from.
 *
 * Sources at `d177b119e9c56c9ddc0b7379ffce52341ec06584`:
 * `apps/desktop/src/plugins/hermes-bots/cron.tsx` (title, schedule label,
 * state derivation, timestamp) and `tools/cronjob_job_args.py:351-399` (the row
 * the Gateway actually sends).
 *
 * The parser is total by contract: a malformed member drops its row or degrades
 * its field, and never crashes the surface or invents a value the Gateway did
 * not send.
 */
class BotsRoutinesParseTest {

    private fun parse(body: String): List<RoutineRow> =
        (parseRoutineJobs(Json.parseToJsonElement(body)) as RoutineJobsParse.Answered).jobs

    private fun one(row: String): RoutineRow =
        parse("""{"success":true,"jobs":[$row]}""").single()

    // ── identity and titles ───────────────────────────────────────────────────

    @Test
    fun `the bot tag is stripped from the title, Desktop's pattern exactly`() {
        assertEquals("Morning", routineTitle("[bot:ops] Morning"))
        assertEquals("Morning", routineTitle("[BOT:OPS]   Morning"))
        assertEquals("Morning", routineTitle("Morning"))
        // A tag with no routine after it is Desktop's own "Untitled job": the
        // name was the tag, so nothing is left to show (`cron.tsx:93-95`).
        assertEquals("Untitled job", routineTitle("[bot:ops]"))
        assertEquals("Untitled job", routineTitle(""))
        assertEquals("Untitled job", routineTitle(null))
        // Mid-name brackets are prose, not a tag: the anchor is what makes the
        // difference between a scope marker and a name.
        assertEquals("Ship [bot:ops] tomorrow", routineTitle("Ship [bot:ops] tomorrow"))
    }

    @Test
    fun `the title never renders the raw state, schedule or backend prose`() {
        val row = one(
            """
            {"job_id":"j1","name":"[bot:ops] Digest","schedule":"every 1440m","state":"scheduled",
             "last_fire_error":"Traceback: backend prose","last_delivery_error":"backend prose",
             "paused_reason":"backend prose","prompt_preview":"backend prose",
             "last_status":"delivery_failed","repeat":"3 times","model":"a-model","workdir":"/somewhere"}
            """,
        )

        // The parsed row carries no member that could render backend text: the
        // error, reason, prompt, model and workdir members are not read at all.
        assertEquals("Digest", row.title)
        assertEquals("Daily", row.scheduleLabel)
        assertEquals("3 times", row.repeat)
        for (prose in listOf("Traceback", "backend prose", "a-model", "/somewhere")) {
            assertFalse(prose, row.toString().contains(prose))
        }
    }

    @Test
    fun `a name that is not a string falls back rather than coercing`() {
        // A numeric or boolean name is not this contract's shape; the row's id
        // is what survives, and the title is Desktop's fallback.
        assertEquals("Untitled job", one("""{"job_id":"j1","name":7}""").title)
        assertEquals("Untitled job", one("""{"job_id":"j1","name":true}""").title)
        assertEquals("Untitled job", one("""{"job_id":"j1","name":null}""").title)
    }

    // ── state derivation ─────────────────────────────────────────────────────

    /** Every `state` literal the pinned Gateway can emit (`cron/jobs.py:525-538`). */
    @Test
    fun `the Gateway's state words map onto the closed set this app renders`() {
        val cases = mapOf(
            "scheduled" to RoutineRunState.Scheduled,
            "running" to RoutineRunState.Scheduled,
            "paused" to RoutineRunState.Paused,
            "completed" to RoutineRunState.Completed,
            "error" to RoutineRunState.Failed,
        )
        for ((wire, expected) in cases) {
            assertEquals(wire, expected, one("""{"job_id":"j","enabled":true,"state":"$wire"}""").state)
        }
    }

    @Test
    fun `disabled preserves completed and unknown authority instead of making them resumable`() {
        val cases = mapOf(
            "scheduled" to RoutineRunState.Paused,
            "running" to RoutineRunState.Paused,
            "paused" to RoutineRunState.Paused,
            "error" to RoutineRunState.Failed,
            "" to RoutineRunState.Paused,
            "completed" to RoutineRunState.Completed,
            "weird" to RoutineRunState.Unknown,
        )
        for ((wire, expected) in cases) {
            val row = one("""{"job_id":"j","enabled":false,"state":"$wire"}""")
            assertEquals(wire, expected, row.state)
            assertFalse(wire, row.active)
        }
    }

    @Test
    fun `a state word of paused is paused even beside enabled true`() {
        // Desktop's own rule is `enabled !== false && state !== 'paused'`
        // (`cron.tsx:489`), pinned by its row test asserting a paused job's
        // status and its absent next-run row (`cron-detail.test.tsx:74-78`).
        // The pinned Gateway cannot emit this combination itself, but if it
        // arrives — an older build, a stale read, another writer — the row
        // reads paused rather than claiming a routine is running on evidence
        // that contradicts itself. `enabled:true` does NOT override
        // `state:"paused"`.
        val row = one("""{"job_id":"j","enabled":true,"state":"paused"}""")

        assertEquals(RoutineRunState.Paused, row.state)
        assertFalse(row.active)
    }

    @Test
    fun `an unknown state word renders no state word and no running dot`() {
        // `effective_job_state` passes an unrecognised stored state through
        // verbatim, so the wire is an open set. Echoing it would print backend
        // vocabulary; painting an active dot would promise a run nothing here
        // licenses. The row is inert and says nothing either way.
        val row = one("""{"job_id":"j","enabled":true,"state":"hibernating"}""")

        assertEquals(RoutineRunState.Unknown, row.state)
        assertFalse(row.active)
        assertEquals("Untitled job", row.title)
    }

    @Test
    fun `an absent or malformed enabled is not read as false`() {
        // `enabled` decides whether a row claims paused, so `"false"` and `0`
        // must not be coerced into the value `false`: a malformed member proves
        // nothing, and the row's `state` is then the only evidence left.
        for (row in listOf(
            """{"job_id":"j","state":"scheduled"}""",
            """{"job_id":"j","enabled":"false","state":"scheduled"}""",
            """{"job_id":"j","enabled":0,"state":"scheduled"}""",
            """{"job_id":"j","enabled":null,"state":"scheduled"}""",
        )) {
            assertEquals(row, RoutineRunState.Scheduled, one(row).state)
        }
        // ...and a literal `false` is honoured.
        assertEquals(RoutineRunState.Paused, one("""{"job_id":"j","enabled":false,"state":"scheduled"}""").state)
    }

    @Test
    fun `a completed routine has no next run and is not active`() {
        val row = one("""{"job_id":"j","enabled":true,"state":"completed","next_run_at":null}""")

        assertEquals(RoutineRunState.Completed, row.state)
        assertFalse(row.active)
        assertNull(row.nextRunMillis)
    }

    @Test
    fun `a failed recurring routine is still active`() {
        // `error` here is `compute_next_run` having failed on a recurring job
        // (`cron/jobs.py:546-550`): the occurrence is still coming, so the row
        // must not read as finished.
        val row = one("""{"job_id":"j","enabled":true,"state":"error"}""")

        assertEquals(RoutineRunState.Failed, row.state)
        assertTrue(row.active)
    }

    // ── schedule labels ──────────────────────────────────────────────────────

    @Test
    fun `Desktop's schedule labels are reproduced from its own patterns`() {
        // `cron.tsx:288-324`, case for case.
        val cases = mapOf(
            "once in 20 minutes" to "Once (20 minutes)",
            "30m" to "Once (30m)",
            "2h" to "Once (2h)",
            "1d" to "Once (1d)",
            "every 1m" to "Every 1m",
            "every 15m" to "Every 15m",
            "every 60m" to "Hourly",
            "every 120m" to "Every 2h",
            "every 1440m" to "Daily",
            "every 2880m" to "Every 2 days",
            "0 9 * * *" to "0 9 * * *",
            "weekdays at 9am" to "weekdays at 9am",
            "" to "",
        )
        for ((wire, expected) in cases) {
            assertEquals(wire, expected, routineScheduleLabel(wire))
        }
    }

    @Test
    fun `an unparseable schedule is shown as the Gateway's own string`() {
        // `schedule` is a display string on this wire (`types.ts:268-270`), so
        // the only honest alternative to showing it would be showing nothing.
        val row = one("""{"job_id":"j","schedule":"0 9 * * 1-5"}""")

        assertEquals("0 9 * * 1-5", row.scheduleLabel)
    }

    @Test
    fun `a schedule that is not a string is not labelled`() {
        assertEquals("", one("""{"job_id":"j","schedule":7}""").scheduleLabel)
        assertEquals("", one("""{"job_id":"j","schedule":null}""").scheduleLabel)
        assertEquals("", one("""{"job_id":"j"}""").scheduleLabel)
    }

    @Test
    fun `a zero or negative interval is not labelled as a schedule`() {
        // `every 0m` would divide into "Every 0m", which reads as a schedule
        // that fires constantly; the raw string is the honest answer. A real
        // `every 1440m` still humanises — the two are only the same call.
        assertEquals("every 0m", routineScheduleLabel("every 0m"))
        assertEquals("Daily", routineScheduleLabel("every 1440m"))
    }

    // ── timestamps ───────────────────────────────────────────────────────────

    @Test
    fun `an iso instant with an offset is read as an epoch instant`() {
        // `next_run_at` is `datetime.isoformat()` on the Gateway
        // (`cron/jobs.py:1155`), so it always carries an offset.
        val row = one("""{"job_id":"j","next_run_at":"2026-09-18T09:00:00+00:00"}""")

        assertEquals(1_789_722_000_000L, row.nextRunMillis)
    }

    @Test
    fun `an unreadable next run leaves the label absent rather than anchored to the device`() {
        for (value in listOf("""null""", "\"soon\"", "7", "\"\"", "\"2026-09-18\"")) {
            assertNull(value, one("""{"job_id":"j","next_run_at":$value}""").nextRunMillis)
        }
        assertNull(one("""{"job_id":"j"}""").nextRunMillis)
    }

    @Test
    fun `the relative label uses the coarsest unit that describes the distance`() {
        // `Intl.RelativeTimeFormat(style:'short')` over `lib/time.ts:38-56`.
        val now = 1_789_722_000_000L
        val cases = mapOf(
            30_000L to "in 30 sec",
            60_000L to "in 1 min",
            5 * 60_000L to "in 5 min",
            90 * 60_000L to "in 2 hr",
            14 * 3_600_000L to "in 14 hr",
            24 * 3_600_000L to "in 1 day",
            3 * 86_400_000L to "in 3 days",
        )
        for ((delta, expected) in cases) {
            assertEquals(delta.toString(), expected, routineRelativeLabel(now + delta, now))
        }
        assertEquals("30 sec ago", routineRelativeLabel(now - 30_000L, now))
    }

    @Test
    fun `a rounding boundary goes to the coarser unit at half`() {
        val now = 0L
        // 89_999 ms is 1.49998 min; 90_000 ms is exactly 1.5 min, which
        // `Intl` rounds away from zero to 2.
        assertEquals("in 1 min", routineRelativeLabel(89_999L, now))
        assertEquals("in 2 min", routineRelativeLabel(90_000L, now))
    }

    // ── legacy delegation ────────────────────────────────────────────────────

    @Test
    fun `a legacy delegated routine is recognised from its prompt wrapper`() {
        // `cron.tsx:97-101`: a tagged job whose prompt (or preview) starts with
        // Desktop's other-profile delegation wrapper.
        val marked = one(
            """
            {"job_id":"j","name":"[bot:ops] Digest",
             "prompt_preview":"You are running the scheduled routine \"Digest\" for agent 'ops'. "}
            """,
        )

        assertTrue(marked.legacyDelegated)
    }
    @Test
    fun `an untagged job is never a legacy delegated routine`() {
        // Desktop requires the tag as well as the wrapper: an untagged job in
        // the launch profile's own store is that profile's own routine.
        val row = one(
            """
            {"job_id":"j","name":"Digest",
             "prompt_preview":"You are running the scheduled routine \"Digest\" for agent 'ops'. "}
            """,
        )

        assertFalse(row.legacyDelegated)
    }

    @Test
    fun `a tagged job with an ordinary prompt is not legacy`() {
        assertFalse(one("""{"job_id":"j","name":"[bot:ops] Digest","prompt_preview":"Summarize."}""").legacyDelegated)
        assertFalse(one("""{"job_id":"j","name":"[bot:ops] Digest"}""").legacyDelegated)
    }

    @Test
    fun `the prompt preview is preferred and the full prompt is the fallback`() {
        // `cron.tsx:98`: `prompt_preview` when it is a string, else `prompt`.
        // The wrapper is written with the JSON escape for its quotes, because
        // these bodies are Kotlin raw strings and `\"` is the JSON escape, not
        // a Kotlin one.
        assertTrue(
            one(
                """{"job_id":"j","name":"[bot:ops] D","prompt_preview":"You are running the scheduled routine \"Digest\" for agent 'ops'. "}""",
            ).legacyDelegated,
        )
        assertTrue(
            one(
                """{"job_id":"j","name":"[bot:ops] D","prompt":"You are running the scheduled routine \"Digest\" for agent 'ops'. "}""",
            ).legacyDelegated,
        )
        assertFalse(
            one(
                """{"job_id":"j","name":"[bot:ops] D","prompt":"You are running the scheduled routine \"Digest\" for agent 'ops'. ","prompt_preview":"Summarize."}""",
            ).legacyDelegated,
        )
    }

    @Test
    fun `a legacy routine is listed and never claimed as paused by this app`() {
        // This slice issues no mutation, so a job it did not pause must not be
        // labelled paused. Its state is whatever the Gateway said.
        val row = one(
            """
            {"job_id":"j","name":"[bot:ops] Digest","enabled":true,"state":"scheduled",
             "prompt_preview":"You are running the scheduled routine \"Digest\" for agent 'ops'. "}
            """,
        )

        assertTrue(row.legacyDelegated)
        assertEquals(RoutineRunState.Scheduled, row.state)
        assertTrue(row.active)
    }

    // ── the whole row, as the Gateway sends it ───────────────────────────────

    @Test
    fun `the pinned row shape parses without reaching for a member it does not send`() {
        // `tools/cronjob_job_args.py:358-384` in full, including the members
        // this app deliberately does not read (`latest_execution` is on the job
        // record list_jobs returns, never on the formatted row).
        val row = one(
            """
            {"job_id":"7b1","name":"[bot:ops] Morning briefing",
             "skill":null,"skills":[],
             "prompt_preview":"Summarize my unread threads...",
             "model":"some-model","provider":"some-provider","base_url":"http://example.invalid",
             "schedule":"0 9 * * *","repeat":"forever","deliver":"local",
             "next_run_at":"2026-09-18T09:00:00+00:00","last_run_at":"2026-09-17T09:00:00+00:00",
             "last_status":"ok","last_delivery_error":null,"last_delivery_unverified":null,
             "last_fire_error":null,"last_error":null,"enabled":true,"state":"scheduled",
             "paused_at":null,"paused_reason":null,"latest_execution":{"status":"ok"}}
            """,
        )

        assertEquals("7b1", row.id)
        assertEquals("Morning briefing", row.title)
        assertEquals("0 9 * * *", row.scheduleLabel)
        assertEquals("forever", row.repeat)
        assertEquals(1_789_722_000_000L, row.nextRunMillis)
        assertEquals(RoutineRunState.Scheduled, row.state)
        assertEquals("ops", row.taggedBot)
        assertTrue(row.active)
    }

    @Test
    fun `repeat is the Gateway's own display string and is not recomputed here`() {
        // `_repeat_display` (`cronjob_job_args.py:136-143`) already renders
        // `forever` / `once` / `1/1` / `<done>/<n>` / `<n> times`; a client that
        // re-derived it from a number would disagree with the Gateway.
        for (repeat in listOf("forever", "once", "1/1", "3 times", "2/5")) {
            assertEquals(repeat, one("""{"job_id":"j","repeat":"$repeat"}""").repeat)
        }
        assertNull(one("""{"job_id":"j","repeat":null}""").repeat)
        assertNull(one("""{"job_id":"j","repeat":3}""").repeat)
    }
}
