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

    /**
     * Tests that incoming messages arriving before onboarding completion are dropped immediately without any action.
     *
     * Preconditions: Settings firstRunComplete is false.
     * Expected: Outcome is [ProcessingOutcome.SkippedBeforeOnboarding]; no lookups, SMS sends, notifications, logs, or sounds occur.
     */
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

    /**
     * Tests that matching a word in the stop list immediately ignores the message without querying contacts or sending replies.
     *
     * Preconditions: Stop-list contains "promo" and message contains "Big promo inside!\nSTOP".
     * Expected: Outcome is [ProcessingOutcome.Ignored] with [IgnoreReason.STOP_LIST]; an IGNORED log row is written and lookups are skipped.
     */
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

    /**
     * Tests that an opt-out message from a known Google Contacts contact is ignored without sending an auto-reply.
     *
     * Preconditions: Google Contacts lookup returns Found.
     * Expected: Outcome is [ProcessingOutcome.Ignored] with [IgnoreReason.KNOWN_GOOGLE_CONTACT] and no SMS is sent.
     */
    @Test
    fun `case 1 - known google contact is ignored`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.KNOWN_GOOGLE_CONTACT), outcome)
        assertTrue("a known contact must never be replied to", fakes.smsSender.sent.isEmpty())
        assertEquals("Ignored: Known Google Contact", fakes.logDao.inserted.single().ignoreReason)
    }

    /**
     * Tests that an opt-out message from a known HubSpot CRM contact is ignored without sending an auto-reply.
     *
     * Preconditions: HubSpot integration enabled and HubSpot lookup returns Found.
     * Expected: Outcome is [ProcessingOutcome.Ignored] with [IgnoreReason.KNOWN_HUBSPOT_CONTACT] and no SMS is sent.
     */
    @Test
    fun `case 2 - known hubspot contact is ignored`() = runTest {
        fakes.settings.snapshot = snapshot(useHubSpot = true)
        fakes.hubSpot.outcome = ContactLookupOutcome.Found

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.KNOWN_HUBSPOT_CONTACT), outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
        assertEquals("Ignored: Known HubSpot Contact", fakes.logDao.inserted.single().ignoreReason)
    }

    /**
     * Tests that a transient failure or timeout in HubSpot lookup does not abort processing and allows detection to proceed.
     *
     * Preconditions: HubSpot integration enabled and HubSpot returns Failed outcome.
     * Expected: Outcome is [ProcessingOutcome.Detected] with disposition SENT.
     */
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

    /**
     * Tests that HubSpot CRM is never queried when the integration toggle in settings is disabled.
     *
     * Preconditions: useHubSpot is false.
     * Expected: HubSpot repository queried list is empty.
     */
    @Test
    fun `hubspot is never consulted when the toggle is off`() = runTest {
        fakes.settings.snapshot = snapshot(useHubSpot = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue("HubSpot must be bypassed entirely", fakes.hubSpot.queried.isEmpty())
    }

    /**
     * Tests that a sender found in contacts on the first message is served from the in-memory cache on subsequent messages.
     *
     * Preconditions: ContactSource returns Found for initial lookup; pipeline processes two messages sequentially.
     * Expected: ContactSource is queried exactly once across both message processing runs.
     */
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

    /**
     * Tests that inline "STOP" inside marketing copy on a single line does not trigger an opt-out detection.
     *
     * Preconditions: Message "Hello, reply STOP to unsubscribe".
     * Expected: Outcome is [ProcessingOutcome.NoOptOutDetected], no reply sent, no notification, and NO_MATCH logged.
     */
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

    /**
     * Tests that words embedding "stop" on non-final lines do not trigger detection.
     *
     * Preconditions: Message "Postop care instructions\nCall us".
     * Expected: Outcome is [ProcessingOutcome.NoOptOutDetected] and no reply is sent.
     */
    @Test
    fun `case 9 - stop embedded in a word on a non final line does not trigger`() = runTest {
        val outcome = pipeline().process(UNKNOWN_NUMBER, "Postop care instructions\nCall us", now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    /**
     * Tests that an empty message body does not trigger detection.
     *
     * Preconditions: Message body is "".
     * Expected: Outcome is [ProcessingOutcome.NoOptOutDetected] and no reply is sent.
     */
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

    /**
     * Tests that an unknown sender with a non-matching message writes exactly one NO_MATCH log row.
     *
     * Preconditions: Non-matching message body processed for unknown sender.
     * Expected: Outcome is NoOptOutDetected and logDao contains 1 row with eventType NO_MATCH.
     */
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

    /**
     * Tests that a NO_MATCH log row leaves pattern, reply status, and ignore reason columns null.
     *
     * Preconditions: Non-matching message body.
     * Expected: Log row has null matchedPattern, null replyStatus, and null ignoreReason.
     */
    @Test
    fun `no-match row leaves the detection and ignore columns null`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertNull("no pattern matched", row.matchedPattern)
        assertNull("no reply was considered", row.replyStatus)
        assertNull("the message was not ignored", row.ignoreReason)
    }

    /**
     * Tests that long non-matching message previews are truncated to PREVIEW_MAX_LENGTH.
     *
     * Preconditions: 500-character message body.
     * Expected: Logged messagePreview length equals [DetectionLogEntity.PREVIEW_MAX_LENGTH].
     */
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

    /**
     * Tests that messages ignored by the stop-list write an IGNORED log event and never a NO_MATCH event.
     *
     * Preconditions: Stop-list contains "promo" against "Big promo inside!".
     * Expected: Single log row with eventType IGNORED and no NO_MATCH rows.
     */
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

    /**
     * Tests that messages ignored because of a known contact write an IGNORED event and never a NO_MATCH event.
     *
     * Preconditions: ContactSource returns Found for non-matching message body.
     * Expected: Log event is IGNORED and no NO_MATCH events exist.
     */
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

    /**
     * Tests that a successful detection writes a DETECTION log event and never a NO_MATCH event.
     *
     * Preconditions: Opt-out message processed for unknown number.
     * Expected: Log row has eventType DETECTION and no NO_MATCH rows exist.
     */
    @Test
    fun `successful detection logs detection and never no-match`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(LogEventType.DETECTION, fakes.logDao.inserted.single().eventType)
        assertTrue(
            fakes.logDao.inserted.none { it.eventType == LogEventType.NO_MATCH },
        )
    }

    /**
     * Tests that NO_MATCH events are not logged before onboarding has been completed.
     *
     * Preconditions: Settings firstRunComplete is false.
     * Expected: Outcome is SkippedBeforeOnboarding and no log entries are inserted.
     */
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

    /**
     * Tests that a message ending with "STOP" sends an SMS reply with "stop" to the sender's raw address.
     *
     * Preconditions: Message "Hello\nSTOP" for unknown number.
     * Expected: Outcome is Detected(SENT); SMS sent to raw address with body "stop"; log recorded with replyStatus "Reply sent: stop".
     */
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

    /**
     * Tests that a message matching "stop2stop" anywhere sends an SMS reply with "stop".
     *
     * Preconditions: Message "stop2stop this deal".
     * Expected: SMS sent with body "stop".
     */
    @Test
    fun `case 7 - stop2stop anywhere match replies stop`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "stop2stop this deal", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
    }

    /**
     * Tests that a message matching "end2end" anywhere sends an SMS reply with "end".
     *
     * Preconditions: Message "end2end encryption rocks".
     * Expected: SMS sent with body "end".
     */
    @Test
    fun `case 8 - end2end anywhere match replies end`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "end2end encryption rocks", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "end"), fakes.smsSender.sent)
    }

    /**
     * Tests that a message matching "stop to cancel" anywhere sends an SMS reply with "stop".
     *
     * Preconditions: Message "Text STOP to Cancel".
     * Expected: SMS sent with body "stop" and matchedPattern "stop to cancel" logged.
     */
    @Test
    fun `stop to cancel anywhere match replies stop`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "Text STOP to Cancel", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        assertEquals("stop to cancel", fakes.logDao.inserted.single().matchedPattern)
    }

    /**
     * Tests that a message matching "stop to opt-out" anywhere sends an SMS reply with "stop".
     *
     * Preconditions: Message "Reply STOP to opt-out".
     * Expected: SMS sent with body "stop" and matchedPattern "stop to opt-out" logged.
     */
    @Test
    fun `stop to opt-out anywhere match replies stop`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "Reply STOP to opt-out", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        assertEquals("stop to opt-out", fakes.logDao.inserted.single().matchedPattern)
    }

    /**
     * Tests that a message matching "stop to end" anywhere sends an SMS reply with "stop".
     *
     * Preconditions: Message "STOP to end account texts".
     * Expected: SMS sent with body "stop" and matchedPattern "stop to end" logged.
     */
    @Test
    fun `stop to end anywhere match replies stop`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, "STOP to end account texts", now)

        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
        assertEquals("stop to end", fakes.logDao.inserted.single().matchedPattern)
    }

    /**
     * Tests that a short code sender receives the reply at its exact raw short code digits rather than a normalized number.
     *
     * Preconditions: Short code "89887" and fake E.164 conversion set to "+189887".
     * Expected: SMS sent to raw destination "89887".
     */
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

    /**
     * Tests that the auto-reply SMS is dispatched on the exact subscription ID that the incoming message arrived on.
     *
     * Preconditions: subscriptionId set to RECEIVING_SUB_ID.
     * Expected: [SmsSender.sendTextMessage] invoked with RECEIVING_SUB_ID.
     */
    @Test
    fun `reply leaves on the subscription the message arrived on`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)

        assertEquals(RECEIVING_SUB_ID, fakes.smsSender.lastSubscriptionId)
    }

    /**
     * Tests that short code replies are also dispatched on the receiving SIM subscription ID.
     *
     * Preconditions: Short code sender with subscriptionId RECEIVING_SUB_ID.
     * Expected: Reply sent to SHORT_CODE using RECEIVING_SUB_ID.
     */
    @Test
    fun `short code reply also leaves on the receiving subscription`() = runTest {
        // Short codes are carrier- and country-specific, so this is the case that fails hardest
        // in the real world when the reply goes out the wrong SIM.
        pipeline().process(SHORT_CODE, OPT_OUT_BODY, now, subscriptionId = RECEIVING_SUB_ID)

        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
        assertEquals(RECEIVING_SUB_ID, fakes.smsSender.lastSubscriptionId)
    }

    /**
     * Tests that an unspecified/unknown subscription ID is passed through without being substituted.
     *
     * Preconditions: Message processed without explicit subscriptionId.
     * Expected: [SmsSender.UNKNOWN_SUBSCRIPTION_ID] is passed to the sender.
     */
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

    /**
     * Tests that non-default dual-SIM subscription IDs are preserved throughout pipeline routing.
     *
     * Preconditions: subscriptionId set to SECOND_SIM_SUB_ID.
     * Expected: Sender records SECOND_SIM_SUB_ID in sentSubscriptionIds.
     */
    @Test
    fun `a non-default subscription id is not defaulted away`() = runTest {
        // Guards against a future refactor quietly dropping the parameter: the value asserted here
        // is deliberately neither zero nor the unknown sentinel.
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now, subscriptionId = SECOND_SIM_SUB_ID)

        assertEquals(SECOND_SIM_SUB_ID, fakes.smsSender.lastSubscriptionId)
        assertEquals(listOf(SECOND_SIM_SUB_ID), fakes.smsSender.sentSubscriptionIds)
    }

    /**
     * Tests that the three auto-reply safety gates (dry run, alphanumeric, cooldown) still suppress sends when subscription ID is specified.
     *
     * Preconditions: Testing dry run, alphanumeric sender, and active cooldown with explicit subscriptionId.
     * Expected: No SMS sends dispatched across any of the 3 gate conditions.
     */
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

    /**
     * Tests Gate 1: when auto-reply is disabled, detection posts a notification and logs dry run but sends no SMS.
     *
     * Preconditions: Settings autoReplyEnabled is false.
     * Expected: Disposition is SKIPPED_DRY_RUN, no SMS sent, notification is posted, log shows "Reply skipped: dry run", no cooldown recorded.
     */
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

    /**
     * Tests Gate 2: alphanumeric senders (e.g. "PROMO") post notifications and log skips without attempting to send an SMS.
     *
     * Preconditions: Sender address is alphanumeric "PROMO".
     * Expected: Disposition is SKIPPED_ALPHANUMERIC, no SMS sent, notification posted, log shows "Reply skipped: alphanumeric sender".
     */
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

    /**
     * Tests Gate 3: a second opt-out message from the same sender within the 24-hour cooldown window suppresses the reply.
     *
     * Preconditions: Sender has a recorded reply timestamp 1 hour prior.
     * Expected: Disposition is SKIPPED_COOLDOWN, no SMS sent, notification posted, log shows "Reply skipped: cooldown".
     */
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

    /**
     * Tests that a message arriving just outside the 24-hour cooldown window is permitted to receive a reply.
     *
     * Preconditions: Previous reply timestamp is COOLDOWN_WINDOW_MS + 1 ms ago.
     * Expected: Disposition is SENT and SMS reply is dispatched.
     */
    @Test
    fun `sender just outside the cooldown window does receive a reply`() = runTest {
        val hash = SenderHasher().hash(SHORT_CODE)
        fakes.cooldownDao.rows[hash] = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS - 1

        val outcome = pipeline().process(SHORT_CODE, OPT_OUT_BODY, now)

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
    }

    /**
     * Tests that a cooldown timestamp is recorded only after an auto-reply has successfully been dispatched.
     *
     * Preconditions: Processing a detectable opt-out message with a successful SMS send.
     * Expected: Cooldown DAO contains the hashed sender with the current timestamp.
     */
    @Test
    fun `cooldown row is written only after a successful send`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val hash = SenderHasher().hash(UNKNOWN_NUMBER)
        assertEquals(now, fakes.cooldownDao.rows[hash])
    }

    /**
     * Tests that a failed SMS dispatch does not write a cooldown record, allowing future retries.
     *
     * Preconditions: FakeSmsSender configured to fail (succeed = false).
     * Expected: Disposition is SEND_FAILED and cooldown rows remain empty.
     */
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

    /**
     * Tests that expired cooldown records older than the window are pruned during each pipeline run.
     *
     * Preconditions: Cooldown table contains one stale record and one fresh record.
     * Expected: Stale record is deleted while fresh record is retained.
     */
    @Test
    fun `stale cooldown rows are pruned on every run`() = runTest {
        fakes.cooldownDao.rows["stale"] = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS - 5_000
        fakes.cooldownDao.rows["fresh"] = now - 1_000

        pipeline().process(UNKNOWN_NUMBER, "nothing to see here", now)

        assertNull("stale row pruned", fakes.cooldownDao.rows["stale"])
        assertEquals("fresh row retained", now - 1_000, fakes.cooldownDao.rows["fresh"])
    }

    /**
     * Tests that ingress deduplication identifies identical messages arriving via formatted RCS after raw SMS.
     *
     * Preconditions: First message via SMS from "+12026500977", second identical message 5 seconds later via RCS from "(202) 650-0977".
     * Expected: First message is Detected; second message is Ignored with detail "duplicate".
     */
    @Test
    fun `deduplicator ignores identical message arriving via formatted RCS after raw SMS`() = runTest {
        val p = pipeline()
        val firstOutcome = p.process("+12026500977", OPT_OUT_BODY, now, messageSource = MessageSource.SMS)
        assertTrue("first message is detected", firstOutcome is ProcessingOutcome.Detected)

        val secondOutcome = p.process("(202) 650-0977", OPT_OUT_BODY, now + 5_000L, messageSource = MessageSource.RCS)
        assertTrue("second message is ignored as duplicate", secondOutcome is ProcessingOutcome.Ignored)
        assertEquals("duplicate", (secondOutcome as ProcessingOutcome.Ignored).detail)
    }

    /**
     * Tests that cooldown properly blocks a formatted RCS sender after a raw SMS reply was already sent to the same number.
     *
     * Preconditions: First SMS reply sent to "+12026500977"; distinct message arrives 30 seconds later from "(202) 650-0977" via RCS.
     * Expected: Second message is detected but disposition is SKIPPED_COOLDOWN.
     */
    @Test
    fun `cooldown blocks formatted RCS sender after raw SMS reply sent`() = runTest {
        val p = pipeline()
        // First reply sent to E.164 number via SMS
        val firstOutcome = p.process("+12026500977", "First blast with Stop2Stop", now, messageSource = MessageSource.SMS)
        assertEquals(ReplyDisposition.SENT, (firstOutcome as ProcessingOutcome.Detected).disposition)

        // Second message with different text arrives 30 seconds later via formatted RCS number
        val secondOutcome = p.process("(202) 650-0977", "Second blast with End2End", now + 30_000L, messageSource = MessageSource.RCS)
        assertEquals(ReplyDisposition.SKIPPED_COOLDOWN, (secondOutcome as ProcessingOutcome.Detected).disposition)
    }

    // ---------------------------------------------------------------------
    // Sound (cases 11 and 12)
    // ---------------------------------------------------------------------

    /**
     * Tests that the alert sound player is triggered with the configured URI when beepOnOptOut is enabled and a reply is sent.
     *
     * Preconditions: beepOnOptOut is true and soundFileUri is "content://alert".
     * Expected: soundPlayer records "content://alert".
     */
    @Test
    fun `case 11 - sound plays when enabled and a reply was sent`() = runTest {
        fakes.settings.snapshot = snapshot(beepOnOptOut = true, soundFileUri = "content://alert")

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(listOf("content://alert"), fakes.soundPlayer.played)
    }

    /**
     * Tests that no sound plays when beepOnOptOut setting is disabled.
     *
     * Preconditions: beepOnOptOut is false.
     * Expected: soundPlayer played list is empty.
     */
    @Test
    fun `case 12 - no sound when the beep setting is off`() = runTest {
        fakes.settings.snapshot = snapshot(beepOnOptOut = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(fakes.soundPlayer.played.isEmpty())
    }

    /**
     * Tests that no sound plays when an auto-reply is skipped even if beepOnOptOut is enabled.
     *
     * Preconditions: autoReplyEnabled is false, beepOnOptOut is true.
     * Expected: soundPlayer played list is empty.
     */
    @Test
    fun `no sound when the reply was skipped even if the beep setting is on`() = runTest {
        fakes.settings.snapshot = snapshot(autoReplyEnabled = false, beepOnOptOut = true)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue("silence when nothing was actually sent", fakes.soundPlayer.played.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Notification setting, contacts permission, and privacy
    // ---------------------------------------------------------------------

    /**
     * Tests that disabling opt-out notifications suppresses notifications while still sending the SMS reply.
     *
     * Preconditions: optOutNotificationEnabled is false.
     * Expected: Notification previews list is empty; SMS is still sent.
     */
    @Test
    fun `notification is suppressed when the setting is off but the reply still sends`() = runTest {
        fakes.settings.snapshot = snapshot(optOutNotificationEnabled = false)

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(fakes.notifier.previews.isEmpty())
        assertEquals(listOf(UNKNOWN_NUMBER to "stop"), fakes.smsSender.sent)
    }

    /**
     * Tests that when READ_CONTACTS permission is denied (returning NotFound), messages are still processed and replied to.
     *
     * Preconditions: ContactSource returns NotFound.
     * Expected: Outcome is Detected with SENT disposition.
     */
    @Test
    fun `case 19 - contacts permission denied still processes and replies`() = runTest {
        // ContactRepository reports NotFound when READ_CONTACTS is missing, so the sender is treated
        // as unknown and processing continues rather than crashing.
        fakes.contactSource.outcome = ContactLookupOutcome.NotFound

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ReplyDisposition.SENT, (outcome as ProcessingOutcome.Detected).disposition)
    }

    /**
     * Tests that exceptions during contacts lookup do not crash the pipeline and allow detection to proceed.
     *
     * Preconditions: ContactSource returns Failed with SecurityException.
     * Expected: Outcome is [ProcessingOutcome.Detected].
     */
    @Test
    fun `contacts lookup failure still proceeds to detection`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Failed("SecurityException")

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertTrue(outcome is ProcessingOutcome.Detected)
    }

    /**
     * Tests that the sender's address is recorded in the detection log on successful opt-out detection.
     *
     * Preconditions: Processing opt-out messages for standard and short code senders.
     * Expected: Logged rows contain the respective sender addresses.
     */
    @Test
    fun `detection log records sender address on successful detection`() = runTest {
        val pipeline = pipeline()

        pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        assertEquals(UNKNOWN_NUMBER, fakes.logDao.inserted.last().senderAddress)

        pipeline.process(SHORT_CODE, OPT_OUT_BODY, now + 10_000L)
        assertEquals(SHORT_CODE, fakes.logDao.inserted.last().senderAddress)
    }

    /**
     * Tests that the sender's address is recorded in the detection log when a message is ignored due to stop list match.
     *
     * Preconditions: Stop list contains "promo".
     * Expected: Logged row has eventType IGNORED and contains sender address.
     */
    @Test
    fun `detection log records sender address on stop list ignore`() = runTest {
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))

        pipeline().process(UNKNOWN_NUMBER, "Big promo inside!\nSTOP", now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.IGNORED, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    /**
     * Tests that the sender's address is recorded in the detection log when a message is ignored as a known contact.
     *
     * Preconditions: ContactSource returns Found.
     * Expected: Logged row has eventType IGNORED and contains sender address.
     */
    @Test
    fun `detection log records sender address on known contact ignore`() = runTest {
        fakes.contactSource.outcome = ContactLookupOutcome.Found

        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.IGNORED, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    /**
     * Tests that the sender's address is recorded in the detection log when a message has no match.
     *
     * Preconditions: Non-matching message body.
     * Expected: Logged row has eventType NO_MATCH and contains sender address.
     */
    @Test
    fun `detection log records sender address on no-match event`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, NO_MATCH_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.NO_MATCH, row.eventType)
        assertEquals(UNKNOWN_NUMBER, row.senderAddress)
    }

    /**
     * Tests that the alphanumeric sender string is recorded in the detection log when reply is skipped for alphanumeric sender.
     *
     * Preconditions: Message from "PROMO".
     * Expected: Logged row has eventType DETECTION and senderAddress "PROMO".
     */
    @Test
    fun `detection log records alphanumeric sender on skipped reply`() = runTest {
        pipeline().process(ALPHANUMERIC, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(LogEventType.DETECTION, row.eventType)
        assertEquals(ALPHANUMERIC, row.senderAddress)
    }

    /**
     * Tests that the message preview stored in the detection log is truncated to PREVIEW_MAX_LENGTH.
     *
     * Preconditions: Message body exceeding 500 characters.
     * Expected: Logged messagePreview length equals [DetectionLogEntity.PREVIEW_MAX_LENGTH].
     */
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

    /**
     * Tests that having an empty opt-out pattern database disables detection entirely.
     *
     * Preconditions: Pattern DAO returns empty list.
     * Expected: Outcome is NoOptOutDetected and no SMS is sent.
     */
    @Test
    fun `deleting every pattern disables detection`() = runTest {
        fakes.patternDao.patterns = emptyList()

        val outcome = pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        assertEquals(ProcessingOutcome.NoOptOutDetected, outcome)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    /**
     * Tests that custom user-added patterns in the DAO are matched and replied to accordingly.
     *
     * Preconditions: Custom pattern "unsubscribe" with END reply type in ANYWHERE mode.
     * Expected: Message "Click here to unsubscribe" results in reply "end".
     */
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

    /**
     * Tests that providing a direct reply key routes the reply through DirectReplySender instead of cellular SmsSender.
     *
     * Preconditions: directReplyKey is "reply-handle-123".
     * Expected: DirectReplySender records ("reply-handle-123", "stop"), cellular SmsSender is not used, log shows "Reply sent: stop".
     */
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

    /**
     * Tests that when directReplyKey is null, cellular SmsSender is used for sending the reply.
     *
     * Preconditions: directReplyKey is null.
     * Expected: SmsSender records the send and DirectReplySender is not used.
     */
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

    /**
     * Tests that duplicate incoming messages within the deduplication TTL window are suppressed.
     *
     * Preconditions: Identical message arrives 5 seconds after the first message.
     * Expected: Second message outcome is Ignored with detail "duplicate" and only 1 SMS is sent.
     */
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

    /**
     * Tests that messages arriving after the deduplication TTL window and cooldown window have passed are processed.
     *
     * Preconditions: Second identical message arrives after deduplication TTL and cooldown window have elapsed.
     * Expected: Second message is processed with SENT disposition.
     */
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

    /**
     * Tests that the detection log defaults the message source to SMS when not explicitly specified.
     *
     * Preconditions: Processing message without messageSource parameter.
     * Expected: Logged row has messageSource = [MessageSource.SMS].
     */
    @Test
    fun `detection log records SMS message source by default`() = runTest {
        pipeline().process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)

        val row = fakes.logDao.inserted.single()
        assertEquals(MessageSource.SMS, row.messageSource)
    }

    /**
     * Tests that explicitly passing messageSource = SMS is recorded in the detection log.
     *
     * Preconditions: messageSource = [MessageSource.SMS].
     * Expected: Logged row has messageSource = [MessageSource.SMS].
     */
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

    /**
     * Tests that RCS message sources are recorded in the detection log upon detection.
     *
     * Preconditions: messageSource = [MessageSource.RCS] and directReplyKey provided.
     * Expected: Logged row has messageSource = [MessageSource.RCS].
     */
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

    /**
     * Tests that RCS message sources are recorded in the detection log when a message is ignored.
     *
     * Preconditions: messageSource = [MessageSource.RCS] and sender is a known contact.
     * Expected: Logged row has eventType IGNORED and messageSource = [MessageSource.RCS].
     */
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

    /**
     * Tests that RCS message sources are recorded in the detection log for NO_MATCH events.
     *
     * Preconditions: messageSource = [MessageSource.RCS] with non-matching body.
     * Expected: Logged row has eventType NO_MATCH and messageSource = [MessageSource.RCS].
     */
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

    /**
     * Tests that MMS message sources are recorded in the detection log upon detection.
     *
     * Preconditions: messageSource = [MessageSource.MMS].
     * Expected: Logged row has messageSource = [MessageSource.MMS].
     */
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

    /**
     * Tests that opt-out detections in group threads skip auto-reply with SKIPPED_GROUP_THREAD disposition.
     *
     * Preconditions: isGroupThread is true and messageSource is MMS.
     * Expected: Disposition is SKIPPED_GROUP_THREAD, no SMS or direct replies sent, notification posted, and log shows "Skipped: Group thread".
     */
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

    /**
     * Tests that 1-to-1 MMS message detections send a reply and record MessageSource.MMS in the log.
     *
     * Preconditions: isGroupThread is false and messageSource is MMS.
     * Expected: Disposition is SENT, SMS reply "stop" sent, and log row records MessageSource.MMS.
     */
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

    /**
     * Tests that MMS ignore (stop list, known contact) and no-match events preserve MessageSource.MMS in the log.
     *
     * Preconditions: Processing MMS messages that match stop list, known contacts, and no-match.
     * Expected: All corresponding log rows retain MessageSource.MMS.
     */
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
