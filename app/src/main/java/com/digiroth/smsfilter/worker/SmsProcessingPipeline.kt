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

import com.digiroth.smsfilter.data.db.dao.AutoReplyCooldownDao
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.AutoReplyCooldownEntity
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.repository.ContactLookupCache
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.ContactSource
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.settings.SettingsSnapshotProvider
import com.digiroth.smsfilter.detection.OptOutDetector
import com.digiroth.smsfilter.detection.OptOutResult
import com.digiroth.smsfilter.detection.StopListMatcher
import com.digiroth.smsfilter.platform.AlertSoundPlayer
import com.digiroth.smsfilter.platform.DetectionNotifier
import com.digiroth.smsfilter.platform.SmsSender
import com.digiroth.smsfilter.util.PhoneNumberNormalizer
import com.digiroth.smsfilter.util.SenderHasher
import com.digiroth.smsfilter.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why an incoming message was ignored without reaching opt-out detection.
 */
enum class IgnoreReason {
    /** A stop-list keyword appeared in the body. */
    STOP_LIST,

    /** The sender matched a saved Google contact. */
    KNOWN_GOOGLE_CONTACT,

    /** The sender matched a HubSpot CRM contact. */
    KNOWN_HUBSPOT_CONTACT,
}

/**
 * What happened to the auto-reply after a detection.
 *
 * Every value other than [SENT] represents a gate that blocked the send. In all cases the
 * detection is still notified and logged.
 */
enum class ReplyDisposition {
    /** The reply was handed to the platform successfully. */
    SENT,

    /** Auto-reply is switched off; the app is in detection-only (dry run) mode. */
    SKIPPED_DRY_RUN,

    /** The sender is an alphanumeric ID and cannot receive an SMS at all. */
    SKIPPED_ALPHANUMERIC,

    /** A reply already went to this sender inside the 24-hour window. */
    SKIPPED_COOLDOWN,

    /** All gates passed but the platform refused the send. */
    SEND_FAILED,
}

/**
 * The outcome of processing one incoming message.
 *
 * Returned so callers and tests can assert on the decision itself rather than inferring it from
 * side effects alone.
 */
sealed interface ProcessingOutcome {

    /**
     * Onboarding has not completed, so the message was dropped untouched: no lookups, no
     * detection, no reply, no notification, and no log row.
     */
    data object SkippedBeforeOnboarding : ProcessingOutcome

    /**
     * The message was ignored.
     *
     * @property reason Why it was ignored.
     * @property detail Supporting detail, such as the stop-list keyword that matched.
     */
    data class Ignored(val reason: IgnoreReason, val detail: String? = null) : ProcessingOutcome

    /** The sender was unknown but the body contained no opt-out signal. */
    data object NoOptOutDetected : ProcessingOutcome

    /**
     * An opt-out signal was detected.
     *
     * @property result The pattern that matched and the keyword to reply with.
     * @property disposition What happened to the reply.
     */
    data class Detected(
        val result: OptOutResult,
        val disposition: ReplyDisposition,
    ) : ProcessingOutcome
}

/**
 * The complete decision logic for one incoming SMS.
 *
 * This class is deliberately free of every `android.*` import. All platform interaction — sending
 * an SMS, posting a notification, playing a sound, reading the clock — is reached through injected
 * interfaces, which is what allows the whole pipeline, including the three auto-reply safety
 * gates, to be exercised in fast JVM unit tests. `SmsLookupWorker` is a thin adapter over this
 * class and contains no decisions of its own.
 *
 * The step order below is fixed by the specification and is load-bearing: the stop-list check runs
 * before any contact lookup so an ignored message costs no network calls, and the onboarding gate
 * runs before everything because the manifest-declared receiver goes live the moment `RECEIVE_SMS`
 * is granted mid-wizard.
 */
@Singleton
class SmsProcessingPipeline @Inject constructor(
    private val settings: SettingsSnapshotProvider,
    private val stopListDao: StopListDao,
    private val optOutPatternDao: OptOutPatternDao,
    private val detectionLogDao: DetectionLogDao,
    private val cooldownDao: AutoReplyCooldownDao,
    private val contactSource: ContactSource,
    private val hubSpotRepository: HubSpotRepository,
    private val contactLookupCache: ContactLookupCache,
    private val stopListMatcher: StopListMatcher,
    private val optOutDetector: OptOutDetector,
    private val phoneNumberNormalizer: PhoneNumberNormalizer,
    private val senderHasher: SenderHasher,
    private val smsSender: SmsSender,
    private val detectionNotifier: DetectionNotifier,
    private val alertSoundPlayer: AlertSoundPlayer,
    private val timeProvider: TimeProvider,
) {

    /**
     * Processes one fully reconstructed incoming message.
     *
     * @param senderAddress The originating address exactly as received. Multi-part messages share
     *   one address, taken from the first segment.
     * @param messageBody The complete message body, with all segments already concatenated.
     * @param receivedAtMillis When the message arrived, in epoch milliseconds.
     * @return The decision reached, including the fate of any auto-reply.
     */
    suspend fun process(
        senderAddress: String,
        messageBody: String,
        receivedAtMillis: Long,
    ): ProcessingOutcome {
        // Step 0 — onboarding gate. Must precede everything: the receiver starts firing as soon as
        // RECEIVE_SMS is granted in wizard step 2, before the user has seen a settings screen or
        // consented to anything being sent on their behalf.
        val snapshot = settings.snapshot()
        if (!snapshot.firstRunComplete) {
            return ProcessingOutcome.SkippedBeforeOnboarding
        }

        // Housekeeping. Done on every run so the cooldown table cannot grow without bound, and
        // before the cooldown check so an expired record can never block a legitimate reply.
        pruneExpiredCooldowns()

        // Step 1 — read the lists once, so one message is judged against one consistent snapshot.
        val stopListKeywords = stopListDao.getAll()
        val patterns = optOutPatternDao.getAll()

        // Step 2 — stop list. First, so an ignored message costs no lookups.
        stopListMatcher.findMatch(messageBody, stopListKeywords)?.let { matched ->
            logIgnored(
                timestamp = receivedAtMillis,
                reason = "Ignored: Matched Stop List word '${matched.keyword}'",
                body = messageBody,
            )
            return ProcessingOutcome.Ignored(IgnoreReason.STOP_LIST, detail = matched.keyword)
        }

        val sender = phoneNumberNormalizer.normalize(senderAddress)

        // Steps 3-4 — is this a known contact? A live cache entry short-circuits both lookups,
        // which is the latency win the cache exists for.
        if (contactLookupCache.isKnownContact(sender.primaryLookupValue)) {
            logIgnored(receivedAtMillis, "Ignored: Known contact (cached)", messageBody)
            return ProcessingOutcome.Ignored(IgnoreReason.KNOWN_GOOGLE_CONTACT, detail = "cached")
        }

        if (contactSource.isKnownContact(sender.primaryLookupValue) == ContactLookupOutcome.Found) {
            contactLookupCache.markKnownContact(sender.primaryLookupValue)
            logIgnored(receivedAtMillis, "Ignored: Known Google Contact", messageBody)
            return ProcessingOutcome.Ignored(IgnoreReason.KNOWN_GOOGLE_CONTACT)
        }

        if (snapshot.useHubSpot) {
            // A Failed outcome deliberately falls through to detection. Treating an outage as
            // "not a contact" is the safe direction: the alternative would silently stop the app
            // working whenever HubSpot is unreachable.
            if (hubSpotRepository.isKnownContact(sender.e164, sender.digits) == ContactLookupOutcome.Found) {
                contactLookupCache.markKnownContact(sender.primaryLookupValue)
                logIgnored(receivedAtMillis, "Ignored: Known HubSpot Contact", messageBody)
                return ProcessingOutcome.Ignored(IgnoreReason.KNOWN_HUBSPOT_CONTACT)
            }
        }

        // Step 5 — opt-out detection against the live pattern list.
        val detection = optOutDetector.detect(messageBody, patterns)
            ?: run {
                // This row exists purely so the user can see that the message was received and
                // examined. Returning silently here used to leave no evidence whatsoever, which
                // made a correctly-processed message indistinguishable from a broadcast the app
                // never got — the app looked broken precisely when it was working.
                logNoMatch(timestamp = receivedAtMillis, body = messageBody)
                return ProcessingOutcome.NoOptOutDetected
            }

        // The notification fires on every detection, before the gates, because the user must learn
        // about a detected opt-out even when no reply was permitted.
        if (snapshot.optOutNotificationEnabled) {
            detectionNotifier.notifyOptOutDetected(preview(messageBody))
        }

        // Step 6 — the three auto-reply gates, in order.
        val disposition = resolveReply(
            snapshot = snapshot,
            sender = sender,
            detection = detection,
        )

        // Step 8 — sound, only for a reply that actually went out.
        if (disposition == ReplyDisposition.SENT && snapshot.beepOnOptOut) {
            alertSoundPlayer.playOptOutAlert(snapshot.soundFileUri)
        }

        // Step 9 — log the detection and the reply's fate.
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = receivedAtMillis,
                eventType = LogEventType.DETECTION,
                matchedPattern = detection.pattern,
                replyStatus = describe(disposition, detection),
                messagePreview = preview(messageBody),
            ),
        )

        return ProcessingOutcome.Detected(detection, disposition)
    }

    /**
     * Applies the three auto-reply gates and, if all pass, sends the reply and records the cooldown.
     *
     * @param snapshot The settings this message is being judged against.
     * @param sender The classified sender.
     * @param detection The pattern that matched.
     * @return What happened to the reply.
     */
    private suspend fun resolveReply(
        snapshot: com.digiroth.smsfilter.data.settings.SettingsSnapshot,
        sender: com.digiroth.smsfilter.util.NormalizedSender,
        detection: OptOutResult,
    ): ReplyDisposition {
        // Gate 1 — master switch. Detection-only mode, and the kill switch if a pattern misfires.
        if (!snapshot.autoReplyEnabled) return ReplyDisposition.SKIPPED_DRY_RUN

        // Gate 2 — repliable sender. An alphanumeric ID cannot receive an SMS at all.
        if (!sender.isRepliable) return ReplyDisposition.SKIPPED_ALPHANUMERIC

        // Gate 3 — cooldown. Prevents an SMS ping-pong loop with an automated responder whose
        // confirmation text itself trips a pattern.
        val senderHash = senderHasher.hash(sender.rawAddress)
        val now = timeProvider.nowMillis()
        val cutoff = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS
        if (cooldownDao.isInCooldown(senderHash, cutoff)) return ReplyDisposition.SKIPPED_COOLDOWN

        // Step 7 — reply to the RAW address. A short code must receive its reply at the exact
        // address it sent from, never at a normalized form.
        val sent = smsSender.sendTextMessage(sender.rawAddress, detection.replyKeyword)
        if (!sent) return ReplyDisposition.SEND_FAILED

        // Recorded only after a successful send, so a failed attempt does not lock out the retry.
        cooldownDao.upsert(AutoReplyCooldownEntity(senderHash = senderHash, lastReplyTimestamp = now))
        return ReplyDisposition.SENT
    }

    private suspend fun pruneExpiredCooldowns() {
        val cutoff = timeProvider.nowMillis() - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS
        cooldownDao.deleteOlderThan(cutoff)
    }

    private suspend fun logIgnored(timestamp: Long, reason: String, body: String) {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = timestamp,
                eventType = LogEventType.IGNORED,
                ignoreReason = reason,
                messagePreview = preview(body),
            ),
        )
    }

    /**
     * Records that a message reached detection and matched nothing.
     *
     * There is no pattern, no reply and no ignore reason to describe, so those columns stay `null`;
     * the timestamp and the preview are the whole point of the row.
     *
     * @param timestamp When the message arrived, in epoch milliseconds.
     * @param body The message body, truncated by [preview] before it is stored.
     */
    private suspend fun logNoMatch(timestamp: Long, body: String) {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = timestamp,
                eventType = LogEventType.NO_MATCH,
                messagePreview = preview(body),
            ),
        )
    }

    /**
     * Builds the log/notification excerpt of a message body.
     *
     * Only the body is ever included — never the sender address, in any form — so no log row or
     * notification can leak a phone number.
     *
     * @param body The message body.
     * @return The body truncated to [DetectionLogEntity.PREVIEW_MAX_LENGTH].
     */
    private fun preview(body: String): String = body.take(DetectionLogEntity.PREVIEW_MAX_LENGTH)

    /**
     * Renders a reply outcome as the log string the specification prescribes.
     *
     * @param disposition What happened to the reply.
     * @param detection The pattern that matched, supplying the keyword for a successful send.
     * @return A human-readable status for the log row.
     */
    private fun describe(disposition: ReplyDisposition, detection: OptOutResult): String =
        when (disposition) {
            ReplyDisposition.SENT -> "Reply sent: ${detection.replyKeyword}"
            ReplyDisposition.SKIPPED_DRY_RUN -> "Reply skipped: dry run"
            ReplyDisposition.SKIPPED_ALPHANUMERIC -> "Reply skipped: alphanumeric sender"
            ReplyDisposition.SKIPPED_COOLDOWN -> "Reply skipped: cooldown"
            ReplyDisposition.SEND_FAILED -> "Reply skipped: send failed"
        }
}
