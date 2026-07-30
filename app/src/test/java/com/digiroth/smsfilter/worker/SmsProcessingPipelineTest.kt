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
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.data.repository.ContactLookupCache
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.settings.SettingsSnapshot
import com.digiroth.smsfilter.detection.OptOutDetector
import com.digiroth.smsfilter.detection.StopListMatcher
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

    private fun pipeline(): SmsProcessingPipeline {
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
        assertTrue("a non-detection writes no log row", fakes.logDao.inserted.isEmpty())
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
    fun `no log row contains the sender address in any form`() = runTest {
        fakes.settings.snapshot = snapshot(useHubSpot = true)
        val pipeline = pipeline()

        pipeline.process(UNKNOWN_NUMBER, OPT_OUT_BODY, now)
        fakes.stopListDao.keywords = listOf(StopListEntity(id = 1, keyword = "promo"))
        pipeline.process(SHORT_CODE, "a promo message", now)

        val digits = UNKNOWN_NUMBER.filter(Char::isDigit)
        fakes.logDao.inserted.forEach { row ->
            val serialized = listOfNotNull(
                row.messagePreview,
                row.matchedPattern,
                row.replyStatus,
                row.ignoreReason,
            ).joinToString(" ")
            assertTrue(
                "log row leaked a sender address: $serialized",
                !serialized.contains(UNKNOWN_NUMBER) &&
                    !serialized.contains(digits) &&
                    !serialized.contains(SHORT_CODE),
            )
        }
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
