/*
 * Copyright (c) 2026 Bill Roth <bill.roth@gmail.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.digiroth.smsfilter.worker

import com.digiroth.smsfilter.data.db.entity.AutoReplyCooldownEntity
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.MessageSource
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.data.repository.ContactLookupCache
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.settings.SettingsSnapshot
import com.digiroth.smsfilter.detection.OptOutDetector
import com.digiroth.smsfilter.detection.StopListMatcher
import com.digiroth.smsfilter.platform.SmsSender
import com.digiroth.smsfilter.util.MessageDeduplicator
import com.digiroth.smsfilter.util.PhoneNumberNormalizer
import com.digiroth.smsfilter.util.SenderHasher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SmsProcessingPipeline], the class that decides whether this app sends an SMS
 * on the user's behalf.
 *
 * Every collaborator is a hand-written fake from [PipelineFakes] — no mocking library is used, and
 * none is available. The fakes record their interactions, which is what allows the three auto-reply
 * safety gates to be verified: each of those gates is defined by something that must *not* happen,
 * and "no message was sent" can only be asserted against a sender you control.
 *
 * Case numbers in test names refer to the manual test-case table in `Prompt.md`.
 */
class SmsProcessingPipelineTest {

    private val fakes = PipelineFakes()

    /** Time chosen well clear of zero so cooldown cutoffs stay positive. */
    private val now: Long = 1_700_000_000_000L

    private fun pipeline(deduplicator: MessageDeduplicator = MessageDeduplicator(fakes.time)): SmsProcessingPipeline {
        fakes.time.now = now
        return SmsProcessingPipeline(
            settings = fakes.settings,
            stopListDao = fakes.stopListDao,
            optOutPatternDao = fakes.patternDao,
            detectionLogDao = fakes.logDao,
            cooldownDao = fakes.cooldownDao,
            contactSource = fakes.contactSource,
            hubSpotRepository = fakes.hubSpot,
            contactLookupCache = ContactLookupCache(fakes.time),
            stopListMatcher = StopListMatcher(),
            optOutDetector = OptOutDetector(),
            phoneNumberNormalizer = PhoneNumberNormalizer(fakes.e164),
            senderHasher = SenderHasher(),
            smsSender = fakes.smsSender,
            directReplySender = fakes.directReplySender,
            messageDeduplicator = deduplicator,
            detectionNotifier = fakes.notifier,
            alertSoundPlayer = fakes.soundPlayer,
            timeProvider = fakes.time,
        )
    }

    private companion object {
        const val UNKNOWN_NUMBER = "+16505551234"
        const val SHORT_CODE = "89887"
        const val ALPHANUMERIC = "PROMO"
        const val OPT_OUT_BODY = "Hello\nSTOP"

        /** A body short enough to survive [DetectionLogEntity.PREVIEW_MAX_LENGTH] untruncated and
         * containing no seeded opt-out pattern, so it always reaches the no-match path. */
        const val NO_MATCH_BODY = "Your appointment is confirmed"

        /**
         * A plausible receiving subscription id. Matches the physical AT&T SIM on the development
         * device, where slot 0 carries subscription id 2.
         */
        const val RECEIVING_SUB_ID = 2

        /**
         * A second, different subscription id, standing in for the other SIM of a dual-SIM pair.
         * Deliberately neither zero nor [SmsSender.UNKNOWN_SUBSCRIPTION_ID] so a value that was
         * silently defaulted away cannot pass.
         */
        const val SECOND_SIM_SUB_ID = 5
    }

    // ---------------------------------------------------------------------
    // Onboarding gate (case 18)
    // ---------------------------------------------------------------------

    @Test
    fun `case 18 - message before onboarding completes is dropped entirely`() = runTest {
        fakes.settings.snapshot = snapshot(firstRunComplete = false)

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.SkippedBeforeOnboarding, outcome)
        assertTrue("no reply", fakes.smsSender.sent.isEmpty())
        assertTrue("no notification", fakes.notifier.previews.isEmpty())
        assertTrue("no log row", fakes.logDao.inserted.isEmpty())
        assertTrue("no contacts lookup", fakes.contactSource.queried.isEmpty())
        assertTrue("no HubSpot lookup", fakes.hubSpot.queried.isEmpty())
        assertTrue("no sound", fakes.soundPlayer.played.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Stop list (case 6)
    // ---------------------------------------------------------------------

    @Test
    fun `case 6 - stop list hit ignores the message without any lookup`() = runTest {
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))

        val outcome = pipeline().process(UNKNOWN_NUMBER, "Big promo inside!\nSTOP", now)

        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.STOP_LIST, "promo"), outcome)
        assertTrue("stop list must short-circuit before any lookup", fakes.contactSource.queried.isEmpty())
        assertTrue(fakes.hubSpot.queried.isEmpty())
        assertTrue(fakes.smsSender.sent.isEmpty())
        assertEquals(LogEventType.IGNORED, fakes.logDao.inserted.single().eventType)
        assertEquals(
            "Ignored: Matched Stop List word 'promo'",
            fakes.logDao.inserted.single().ignoreReason,
        )
    }

    // ---------------------------------------------------------------------
    // Known contacts (cases 1 and 2)
    // ---------------------------------------------------------------------

    @Test
    fun `case 1 - known google contact is ignored`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.KNOWN_GOOGLE_CONTACT), outcome)
        assertTrue("a known contact must never be replied to", fakes.smsSender.sent.isEmpty())
        assertEquals("Ignored: Known Google Contact", fakes.logDao.inserted.single().ignoreReason)
    }

    @Test
    fun `case 2 - known hubspot contact is ignored`() = runTest {
        fakes.settings.snapshot = snapshot(useHubSpot = true)
        fakes.hubSpot.outcome = ContactLookupOutcome.Found

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.KNOWN_HUBSPOT_CONTACT), outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
        assertEquals("Ignored: Known HubSpot Contact", fakes.logDao.inserted.single().ignoreReason)
    }

    @Test
    fun `hubspot lookup failure still proceeds to detection`() = runTest {
        // An outage must not be mistaken for "this is a known contact" — otherwise the app quietly
        // stops working whenever HubSpot is unreachable.
        fakes.settings.snapshot = snapshot(useHubSpot = true)
        fakes.hubSpot.outcome = ContactLookupOutcome.Failed("timeout")

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue("must reach detection", outcome is ProcessingOutcome.Detected)
        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
    }

    @Test
    fun `hubspot is never consulted when the toggle is off`() = runTest {
        fakes.settings.snapshot = snapshot(useHubSpot = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue("HubSpot must be bypassed entirely", fakes.hubSpot.queried.isEmpty())
    }

    @Test
    fun `cached known sender skips both lookups on the second message`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found
        val pipeline = pipeline()

        pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals("the second message must hit the cache", 1, fakes.contactSource.queried.size)
    }

    // ---------------------------------------------------------------------
    // No detection (cases 3, 9, 10)
    // ---------------------------------------------------------------------

    @Test
    fun `case 3 - stop inside marketing copy does not trigger an alert`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, "Hello, reply STOP to unsubscribe", now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
        assertTrue("no notification for a non-detection", fakes.notifier.previews.isEmpty())
        assertEquals(
            "a non-detection is still recorded, for observability",
            LogEventType.NO_MATCH,
            fakes.logDao.inserted.single().eventType,
        )
    }

    @Test
    fun `case 9 - stop embedded in a word on a non final line does not trigger`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, "Postop care instructions\nCall us", now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    @Test
    fun `case 10 - empty message does not trigger`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, "", now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    // ---------------------------------------------------------------------
    // No-match logging
    //
    // A message that reaches detection and matches nothing used to leave no trace at all, which
    // made a healthy app indistinguishable from one that never received the broadcast. These tests
    // pin down that the row is written, that it is written only on that path, and that it never
    // escapes the onboarding gate.
    // ---------------------------------------------------------------------

    @Test
    fun `unknown sender with no opt-out pattern logs exactly one no-match row`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertEquals("exactly one row", 1, fakes.logDao.inserted.size)
        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.NO_MATCH, row.eventType)
        assertEquals(now, row.timestamp)
        assertEquals(NO_MATCH_BODY, row.messagePreview)
    }

    @Test
    fun `no-match row leaves the detection and ignore columns null`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertNull("no pattern matched", row.matchedPattern)
        assertNull("no reply was considered", row.replyStatus)
        assertNull("the message was not ignored", row.ignoreReason)
    }

    @Test
    fun `no-match preview is truncated to the documented maximum`() = runTest {
        val longBody = "y".repeat(500)

        pipeline().process(UNKNOWN_NUMBER, longBody, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.NO_MATCH, row.eventType)
        assertEquals(
            DetectionLogEntity.PREVIEW_MAX_LENGTH,
            row.messagePreview.length,
        )
        assertEquals("y".repeat(DetectionLogEntity.PREVIEW_MAX_LENGTH), row.messagePreview)
    }

    @Test
    fun `stop list ignore logs ignored and never no-match`() = runTest {
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))

        pipeline().process(UNKNOWN_NUMBER, "Big promo inside!", now)

        assertEquals(LogEventType.IGNORED, fakes.logDao.inserted.single().eventType)
        assertTrue(
            "an ignored message must not also be logged as unmatched",
            fakes.logDao.inserted.none { it.eventType == LogEventType.NO_MATCH },
        )
    }

    @Test
    fun `known contact ignore logs ignored and never no-match`() = runTest {
        // Deliberately a body with no opt-out pattern: the ignore must short-circuit before
        // detection, so even here no NO_MATCH row may appear.
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        assertEquals(LogEventType.IGNORED, fakes.logDao.inserted.single().eventType)
        assertTrue(
            fakes.logDao.inserted.none { it.eventType == LogEventType.NO_MATCH },
        )
    }

    @Test
    fun `successful detection logs detection and never no-match`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(LogEventType.DETECTION, fakes.logDao.inserted.single().eventType)
        assertTrue(
            fakes.logDao.inserted.none { it.eventType == LogEventType.NO_MATCH },
        )
    }

    @Test
    fun `no-match logging stays behind the onboarding gate`() = runTest {
        // The gate returns before any logging at all. A non-matching message arriving mid-wizard
        // must therefore still write nothing — adding NO_MATCH must not have punched a hole in it.
        fakes.settings.snapshot = snapshot(firstRunComplete = false)

        val outcome = pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        assertEquals(ProcessingOutcome.SkippedBeforeOnboarding, outcome)
        assertTrue("no log row of any kind", fakes.logDao.inserted.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Successful reply (cases 4, 7, 8)
    // ---------------------------------------------------------------------

    @Test
    fun `case 4 - last line stop sends the stop keyword to the raw address`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(
            ProcessingOutcome.Detected::class,
            outcome::class,
        )
        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        assertEquals("Reply sent: stop", fakes.logDao.inserted.single().replyStatus)
        assertEquals("stop", fakes.logDao.inserted.single().matchedPattern)
    }

    @Test
    fun `case 7 - stop2stop anywhere match replies stop`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "stop2stop this deal", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
    }

    @Test
    fun `case 8 - end2end anywhere match replies end`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "end2end encryption rocks", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "end"), fakes.smsSender.sent)
    }

    @Test
    fun `case 14 - short code receives the reply at its exact raw address`() = runTest {
        // The E.164 fake would return a bogus conversion if consulted; asserting the raw address
        // proves the reply was never addressed to a normalized form.
        fakes.e164.result = "+189887"

        pipeline().process(SHORT_CODE, OPT_OUT_BODY, now)

        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
    }

    // ---------------------------------------------------------------------
    // Dual-SIM routing
    //
    // An aggregator matches a STOP request against the originating MSISDN, so a reply that leaves
    // from the wrong SIM unsubscribes nothing while still recording a cooldown that suppresses the
    // retry for 24 hours. The subscription id must therefore survive the pipeline untouched.
    // ---------------------------------------------------------------------

    @Test
    fun `reply leaves on the subscription the message arrived on`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)

        assertEquals(RECEIVING_SUB_ID, fakes.smsSender.lastSubscriptionId)
    }

    @Test
    fun `short code reply also leaves on the receiving subscription`() = runTest {
        // Short codes are carrier- and country-specific, so this is the case that fails hardest
        // in the real world when the reply goes out the wrong SIM.
        pipeline().process(SHORT_CODE, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)

        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
        assertEquals(RECEIVING_SUB_ID, fakes.smsSender.lastSubscriptionId)
    }

    @Test
    fun `unknown subscription is passed through rather than substituted`() = runTest {
        // The pipeline must not invent a subscription id: only AndroidSmsSender knows what the
        // platform's default SMS subscription is, and that fallback is its job alone.
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(
            SmsSender.UNKNOWN_SUBSCRIPTION_ID,
            fakes.smsSender.lastSubscriptionId,
        )
    }

    @Test
    fun `a non-default subscription id is not defaulted away`() = runTest {
        // Guards against a future refactor quietly dropping the parameter: the value asserted here
        // is deliberately neither zero nor the unknown sentinel.
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now, subscriptionId = SECOND_SIM_SUB_ID)

        assertEquals(SECOND_SIM_SUB_ID, fakes.smsSender.lastSubscriptionId)
        assertEquals(listOf(SECOND_SIM_SUB_ID), fakes.smsSender.sentSubscriptionIds)
    }

    @Test
    fun `the three auto-reply gates still suppress the send with a subscription id present`() =
        runTest {
            fakes.settings.snapshot = snapshot(autoReplyEnabled = false)
            pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)
            assertTrue("dry run", fakes.smsSender.sentSubscriptionIds.isEmpty())

            fakes.settings.snapshot = snapshot()
            pipeline().process(ALPHANUMERIC, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)
            assertTrue("alphanumeric", fakes.smsSender.sentSubscriptionIds.isEmpty())

            fakes.cooldownDao.rows[SenderHasher().hash(SHORT_CODE)] = now
            pipeline().process(SHORT_CODE, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)
            assertTrue("cooldown", fakes.smsSender.sentSubscriptionIds.isEmpty())
        }

    // ---------------------------------------------------------------------
    // Gate 1 — dry run (case 16)
    // ---------------------------------------------------------------------

    @Test
    fun `case 16 - auto-reply off notifies and logs dry run but sends nothing`() = runTest {
        fakes.settings.snapshot = snapshot(autoReplyEnabled = false)

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(
            ReplyDisposition.SKIPPED_DRY_RUN,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertTrue("dry run must send nothing", fakes.smsSender.sent.isEmpty())
        assertEquals("the notification still fires", 1, fakes.notifier.previews.size)
        assertEquals("Reply skipped: dry run", fakes.logDao.inserted.single().replyStatus)
        assertTrue("no cooldown row for a reply that never went out", fakes.cooldownDao.rows.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Gate 2 — unrepliable sender (case 17)
    // ---------------------------------------------------------------------

    @Test
    fun `case 17 - alphanumeric sender notifies and logs but sends nothing`() = runTest {
        val outcome = pipeline().process(ALPHANUMERIC, OPT_OUT_BODY, now)

        assertEquals(
            ReplyDisposition.SKIPPED_ALPHANUMERIC,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertTrue("an alphanumeric ID cannot receive SMS", fakes.smsSender.sent.isEmpty())
        assertEquals(1, fakes.notifier.previews.size)
        assertEquals("Reply skipped: alphanumeric sender", fakes.logDao.inserted.single().replyStatus)
    }

    // ---------------------------------------------------------------------
    // Gate 3 — cooldown (case 15)
    // ---------------------------------------------------------------------

    @Test
    fun `case 15 - second message inside the cooldown window sends nothing`() = runTest {
        val hash = SenderHasher().hash(SHORT_CODE)
        fakes.cooldownDao.rows[hash] = now - (60L * 60L * 1000L) // one hour ago

        val outcome = pipeline().process(SHORT_CODE, OPT_OUT_BODY, now)

        assertEquals(
            ReplyDisposition.SKIPPED_COOLDOWN,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertTrue("cooldown must suppress the second reply", fakes.smsSender.sent.isEmpty())
        assertEquals(1, fakes.notifier.previews.size)
        assertEquals("Reply skipped: cooldown", fakes.logDao.inserted.single().replyStatus)
    }

    @Test
    fun `sender just outside the cooldown window does receive a reply`() = runTest {
        val hash = SenderHasher().hash(SHORT_CODE)
        fakes.cooldownDao.rows[hash] = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS - 1

        val outcome = pipeline().process(SHORT_CODE, OPT_OUT_BODY, now)

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
    }

    @Test
    fun `cooldown row is written only after a successful send`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val hash = SenderHasher().hash(UNKNOWN_NUMBER)
        assertEquals(now, fakes.cooldownDao.rows[hash])
    }

    @Test
    fun `failed send writes no cooldown row so the next message can retry`() = runTest {
        fakes.smsSender.succeed = false

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(
            ReplyDisposition.SEND_FAILED,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertTrue("a failed send must not lock out the retry", fakes.cooldownDao.rows.isEmpty())
    }

    @Test
    fun `stale cooldown rows are pruned on every run`() = runTest {
        fakes.cooldownDao.rows["stale"] = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS - 5_000
        fakes.cooldownDao.rows["fresh"] = now - 1_000

        pipeline().process(UNKNOWN_NUMBER, "nothing to see here", now)

        assertNull("stale row pruned", fakes.cooldownDao.rows["stale"])
        assertEquals("fresh row retained", now - 1_000, fakes.cooldownDao.rows["fresh"])
    }

    // ---------------------------------------------------------------------
    // Sound (cases 11 and 12)
    // ---------------------------------------------------------------------

    @Test
    fun `case 11 - sound plays when enabled and a reply was sent`() = runTest {
        fakes.settings.snapshot = snapshot(beepOnOptOut = true, soundFileUri = "content://alert")

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(listOf("content://alert"), fakes.soundPlayer.played)
    }

    @Test
    fun `case 12 - no sound when the beep setting is off`() = runTest {
        fakes.settings.snapshot = snapshot(beepOnOptOut = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(fakes.soundPlayer.played.isEmpty())
    }

    @Test
    fun `no sound when the reply was skipped even if the beep setting is on`() = runTest {
        fakes.settings.snapshot = snapshot(autoReplyEnabled = false, beepOnOptOut = true)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue("silence when nothing was actually sent", fakes.soundPlayer.played.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Notification setting, contacts permission, and privacy
    // ---------------------------------------------------------------------

    @Test
    fun `notification is suppressed when the setting is off but the reply still sends`() = runTest {
        fakes.settings.snapshot = snapshot(optOutNotificationEnabled = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(fakes.notifier.previews.isEmpty())
        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
    }

    @Test
    fun `case 19 - contacts permission denied still processes and replies`() = runTest {
        // ContactRepository reports NotFound when READ_CONTACTS is missing, so the sender is treated
        // as unknown and processing continues rather than crashing.
        fakes.contactSource.outcome = ContactLookupOutcome.NotFound

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
    }

    @Test
    fun `contacts lookup failure still proceeds to detection`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Failed("SecurityException")

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(outcome is ProcessingOutcome.Detected)
    }

    @Test
    fun `detection log records sender address on successful detection`() = runTest {
        val pipeline = pipeline()

        pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        assertEquals(UNKNOWN_NUMBER, fakes.logDao.inserted.last().senderAddress)

        pipeline.process(SHORT_CODE, OPT_OUT_BODY, now + 10_000L)
        assertEquals(SHORT_CODE, fakes.logDao.inserted.last().senderAddress)
    }

    @Test
    fun `detection log records sender address on stop list ignore`() = runTest {
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))

        pipeline().process(UNKNOWN_NUMBER, "Big promo inside!\nSTOP", now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.IGNORED, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    @Test
    fun `detection log records sender address on known contact ignore`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.IGNORED, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    @Test
    fun `detection log records sender address on no-match event`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.NO_MATCH, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    @Test
    fun `detection log records alphanumeric sender on skipped reply`() = runTest {
        pipeline().process(ALPHANUMERIC, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.DETECTION, row.eventType)
        assertEquals(ALPHANUMERIC, row.senderAddress)
    }

    @Test
    fun `message preview is truncated to the documented maximum`() = runTest {
        val longBody = "x".repeat(500) + "\nSTOP"

        pipeline().process(UNKNOWN_NUMBER, longBody, now)

        assertEquals(
            DetectionLogEntity.PREVIEW_MAX_LENGTH,
            fakes.logDao.inserted.single().messagePreview.length,
        )
    }

    // ---------------------------------------------------------------------
    // Pattern list is read live, not hardcoded
    // ---------------------------------------------------------------------

    @Test
    fun `deleting every pattern disables detection`() = runTest {
        fakes.patternDao.patterns = emptyList()

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    @Test
    fun `a user added pattern is honoured`() = runTest {
        fakes.patternDao.patterns = listOf(
            OptOutPatternEntity(
                pattern = "unsubscribe",
                replyType = ReplyType.END,
                matchMode = MatchMode.ANYWHERE,
            ),
        )

        pipeline().process(UNKNOWN_NUMBER, "Click here to unsubscribe", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "end"), fakes.smsSender.sent)
    }

    // ---------------------------------------------------------------------
    // RCS Direct Reply & Ingress Deduplication
    // ---------------------------------------------------------------------

    @Test
    fun `direct reply key routes through DirectReplySender when provided`() = runTest {
        val outcome = pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            directReplyKey = "reply-handle-123",
        )

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
        assertEquals(listOf("reply-handle-123" to "stop"), fakes.directReplySender.sent)
        assertTrue("cellular SMS sender should not be used for direct reply", fakes.smsSender.sent.isEmpty())
        assertEquals("Reply sent: stop", fakes.logDao.inserted.single().replyStatus)
    }

    @Test
    fun `cellular reply routes through SmsSender when direct reply key is null`() = runTest {
        val outcome = pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            directReplyKey = null,
        )

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        assertTrue("direct reply sender should not be used for cellular SMS", fakes.directReplySender.sent.isEmpty())
    }

    @Test
    fun `deduplication suppresses duplicate message within window`() = runTest {
        val pipeline = pipeline()

        val outcome1 = pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        assertEquals(ReplyDisposition.SENT, (outcome1 as ProcessingOutcome.Detected).disposition)
        assertEquals(1, fakes.smsSender.sent.size)

        // Same sender and body 5 seconds later
        val outcome2 = pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now + 5_000L)
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.STOP_LIST, "duplicate"), outcome2)
        assertEquals("no second SMS sent", 1, fakes.smsSender.sent.size)
    }

    @Test
    fun `message after deduplication TTL window is not suppressed by deduplicator`() = runTest {
        val pipeline = pipeline()

        val outcome1 = pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        assertEquals(ReplyDisposition.SENT, (outcome1 as ProcessingOutcome.Detected).disposition)

        // Advance time past both deduplication TTL and cooldown window to allow subsequent message
        val newTime = now + AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS + 10_000L
        fakes.time.now = newTime

        val outcome2 = pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, newTime)
        assertEquals(ReplyDisposition.SENT, (outcome2 as ProcessingOutcome.Detected).disposition)
        assertEquals(2, fakes.smsSender.sent.size)
    }

    // ---------------------------------------------------------------------
    // MessageSource designator logging
    // ---------------------------------------------------------------------

    @Test
    fun `detection log records SMS message source by default`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(MessageSource.SMS, row.messageSource)
    }

    @Test
    fun `detection log records explicit SMS message source`() = runTest {
        pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            messageSource = MessageSource.SMS,
        )

        val row = fakes.logDao.inserted.single()
        assertEquals(MessageSource.SMS, row.messageSource)
    }

    @Test
    fun `detection log records RCS message source on detection`() = runTest {
        pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            directReplyKey = "reply-123",
            messageSource = MessageSource.RCS,
        )

        val row = fakes.logDao.inserted.single()
        assertEquals(MessageSource.RCS, row.messageSource)
    }

    @Test
    fun `detection log records RCS message source on ignored message`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            messageSource = MessageSource.RCS,
        )

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.IGNORED, row.eventType)
        assertEquals(MessageSource.RCS, row.messageSource)
    }

    @Test
    fun `detection log records RCS message source on no-match event`() = runTest {
        pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = NO_MATCH_BODY,
            receivedAtMillis = now,
            messageSource = MessageSource.RCS,
        )

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.NO_MATCH, row.eventType)
        assertEquals(MessageSource.RCS, row.messageSource)
    }

    @Test
    fun `detection log records MMS message source`() = runTest {
        pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            messageSource = MessageSource.MMS,
        )

        val row = fakes.logDao.inserted.single()
        assertEquals(MessageSource.MMS, row.messageSource)
    }

    @Test
    fun `detection in group thread logs detection and skips auto reply with SKIPPED_GROUP_THREAD`() = runTest {
        val outcome = pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            isGroupThread = true,
            messageSource = MessageSource.MMS,
        )

        assertEquals(
            ReplyDisposition.SKIPPED_GROUP_THREAD,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertTrue("group thread messages must not receive auto-replies", fakes.smsSender.sent.isEmpty())
        assertTrue("no direct reply sent for group thread", fakes.directReplySender.sent.isEmpty())
        assertEquals(1, fakes.notifier.previews.size)
        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.DETECTION, row.eventType)
        assertEquals("Skipped: Group thread", row.replyStatus)
        assertEquals(MessageSource.MMS, row.messageSource)
        assertTrue("no cooldown row for a suppressed reply", fakes.cooldownDao.rows.isEmpty())
    }

    @Test
    fun `1 to 1 MMS message detection sends reply and records MessageSource MMS`() = runTest {
        val outcome = pipeline().process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now,
            isGroupThread = false,
            messageSource = MessageSource.MMS,
        )

        assertEquals(
            ReplyDisposition.SENT,
            (outcome as ProcessingOutcome.Detected).disposition,
        )
        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.DETECTION, row.eventType)
        assertEquals("Reply sent: stop", row.replyStatus)
        assertEquals(MessageSource.MMS, row.messageSource)
    }

    @Test
    fun `MMS ignore and no-match preserve MessageSource MMS`() = runTest {
        val p1 = pipeline()
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))
        val stopOutcome = p1.process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = "Big promo inside!\nSTOP",
            receivedAtMillis = now,
            messageSource = MessageSource.MMS,
        )
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.STOP_LIST, "promo"), stopOutcome)
        assertEquals(MessageSource.MMS, fakes.logDao.inserted.last().messageSource)
        assertEquals(LogEventType.IGNORED, fakes.logDao.inserted.last().eventType)

        val p2 = pipeline()
        fakes.stopListDao.keywords = emptyList()
        fakes.contactSource.outcome = ContactLookupOutcome.Found
        val contactOutcome = p2.process(
            senderAddress = UNKNOWN_NUMBER,
            messageBody = OPT_OUT_BODY,
            receivedAtMillis = now + 1_000L,
            messageSource = MessageSource.MMS,
        )
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.KNOWN_GOOGLE_CONTACT), contactOutcome)
        assertEquals(MessageSource.MMS, fakes.logDao.inserted.last().messageSource)
        assertEquals(LogEventType.IGNORED, fakes.logDao.inserted.last().eventType)

        val p3 = pipeline()
        fakes.contactSource.outcome = ContactLookupOutcome.NotFound
        val noMatchOutcome = p3.process(
            senderAddress = "+16505559999",
            messageBody = NO_MATCH_BODY,
            receivedAtMillis = now + 2_000L,
            messageSource = MessageSource.MMS,
        )
        assertEquals(ProcessingOutcome.NoOptOutDetected, noMatchOutcome)
        assertEquals(MessageSource.MMS, fakes.logDao.inserted.last().messageSource)
        assertEquals(LogEventType.NO_MATCH, fakes.logDao.inserted.last().eventType)
    }

    private fun snapshot(
        firstRunComplete: Boolean = true,
        autoReplyEnabled: Boolean = true,
        useHubSpot: Boolean = false,
        beepOnOptOut: Boolean = false,
        soundFileUri: String? = null,
        optOutNotificationEnabled: Boolean = true,
    ): SettingsSnapshot = SettingsSnapshot(
        firstRunComplete = firstRunComplete,
        autoReplyEnabled = autoReplyEnabled,
        useHubSpot = useHubSpot,
        beepOnOptOut = beepOnOptOut,
        soundFileUri = soundFileUri,
        optOutNotificationEnabled = optOutNotificationEnabled,
    )
}
