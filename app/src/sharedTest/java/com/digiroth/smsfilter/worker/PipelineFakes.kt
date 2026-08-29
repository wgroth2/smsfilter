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
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.ContactSource
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.settings.SettingsSnapshot
import com.digiroth.smsfilter.data.settings.SettingsSnapshotProvider
import com.digiroth.smsfilter.platform.AlertSoundPlayer
import com.digiroth.smsfilter.platform.DetectionNotifier
import com.digiroth.smsfilter.platform.DirectReplySender
import com.digiroth.smsfilter.platform.SmsSender
import com.digiroth.smsfilter.util.E164Formatter
import com.digiroth.smsfilter.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Hand-written fakes for every [SmsProcessingPipeline] collaborator.
 *
 * Written by hand rather than generated because no mocking library is available — and because
 * recording fakes read better here than stubs would: the auto-reply gates are all assertions about
 * something *not* happening, which reads naturally as `sent.isEmpty()`.
 *
 * Only the DAO methods the pipeline actually calls carry behaviour; the rest throw
 * [NotImplementedError] so an unexpected call fails loudly instead of silently returning a default.
 */
class PipelineFakes {

    /** Settings source; assign [FakeSettings.snapshot] to change what the pipeline reads. */
    val settings = FakeSettings()

    /** Stop-list source; assign [FakeStopListDao.keywords]. */
    val stopListDao = FakeStopListDao()

    /** Pattern source; defaults to the four seeded patterns. */
    val patternDao = FakeOptOutPatternDao()

    /** Records every log row the pipeline writes. */
    val logDao = FakeDetectionLogDao()

    /** In-memory stand-in for the cooldown table, keyed by sender hash. */
    val cooldownDao = FakeCooldownDao()

    /** Google Contacts stand-in; assign [FakeContactSource.outcome]. */
    val contactSource = FakeContactSource()

    /** HubSpot stand-in; assign [FakeHubSpotRepository.outcome]. */
    val hubSpot = FakeHubSpotRepository()

    /** Records every SMS the pipeline attempts to send. */
    val smsSender = FakeSmsSender()

    /** Records every direct reply the pipeline attempts to send. */
    val directReplySender = FakeDirectReplySender()

    /** Records every notification the pipeline posts. */
    val notifier = FakeDetectionNotifier()

    /** Records every sound the pipeline plays. */
    val soundPlayer = FakeAlertSoundPlayer()

    /** Mutable clock; set [FakeTimeProvider.now] to control cooldown and cache boundaries. */
    val time = FakeTimeProvider()

    /** E.164 conversion stand-in, so no Android framework call is ever made. */
    val e164 = FakeE164Formatter()

    class FakeSettings : SettingsSnapshotProvider {
        var snapshot: SettingsSnapshot = SettingsSnapshot(
            firstRunComplete = true,
            autoReplyEnabled = true,
            useHubSpot = false,
            beepOnOptOut = false,
            soundFileUri = null,
            optOutNotificationEnabled = true,
        )

        override suspend fun snapshot(): SettingsSnapshot = snapshot
    }

    class FakeTimeProvider(var now: Long = 0L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    class FakeE164Formatter(var result: String? = null) : E164Formatter {
        override fun format(rawNumber: String, defaultRegion: String): String? {
            if (result != null) return result
            val digits = rawNumber.filter(Char::isDigit)
            return when {
                rawNumber.startsWith("+") -> "+$digits"
                digits.length == 10 -> "+1$digits"
                digits.length == 11 && digits.startsWith("1") -> "+$digits"
                else -> null
            }
        }
    }

    class FakeSmsSender : SmsSender {
        /** Every attempted send, as (destination, body) pairs, in order. */
        val sent: MutableList<Pair<String, String>> = mutableListOf()

        /**
         * The subscription id of every successful send, in the same order as [sent] and index-
         * aligned with it.
         *
         * Kept as a parallel list rather than a third component of [sent] so the fifteen-odd
         * existing assertions on (destination, body) pairs keep reading as they do today; SIM
         * routing is an orthogonal concern and is asserted separately.
         */
        val sentSubscriptionIds: MutableList<Int> = mutableListOf()

        /** The subscription id of the most recent successful send, or `null` if nothing was sent. */
        val lastSubscriptionId: Int?
            get() = sentSubscriptionIds.lastOrNull()

        /** Set to `false` to simulate the platform refusing the send. */
        var succeed: Boolean = true

        override fun sendTextMessage(
            destinationAddress: String,
            body: String,
            subscriptionId: Int,
        ): Boolean {
            if (!succeed) return false
            sent += destinationAddress to body
            sentSubscriptionIds += subscriptionId
            return true
        }
    }

    class FakeDirectReplySender : DirectReplySender {
        /** Every attempted direct reply send, as (replyKey, body) pairs. */
        val sent: MutableList<Pair<String, String>> = mutableListOf()

        /** Set to `false` to simulate direct reply dispatch failure. */
        var succeed: Boolean = true

        override fun sendDirectReply(replyKey: String, body: String): Boolean {
            if (!succeed) return false
            sent += replyKey to body
            return true
        }
    }

    class FakeDetectionNotifier : DetectionNotifier {
        /** Every notification preview posted, in order. */
        val previews: MutableList<String> = mutableListOf()

        override fun notifyOptOutDetected(messagePreview: String) {
            previews += messagePreview
        }
    }

    class FakeAlertSoundPlayer : AlertSoundPlayer {
        /** Every sound URI played; a `null` entry means the system default was requested. */
        val played: MutableList<String?> = mutableListOf()

        override fun playOptOutAlert(soundFileUri: String?) {
            played += soundFileUri
        }
    }

    class FakeContactSource : ContactSource {
        var outcome: ContactLookupOutcome = ContactLookupOutcome.NotFound

        /** Every lookup value queried, in order. Empty proves no lookup happened. */
        val queried: MutableList<String> = mutableListOf()

        override suspend fun isKnownContact(lookupValue: String): ContactLookupOutcome {
            queried += lookupValue
            return outcome
        }
    }

    class FakeHubSpotRepository : HubSpotRepository {
        var outcome: ContactLookupOutcome = ContactLookupOutcome.NotFound

        /** Every lookup performed, as (e164, rawDigits) pairs. Empty proves HubSpot was bypassed. */
        val queried: MutableList<Pair<String?, String>> = mutableListOf()

        override suspend fun isKnownContact(
            e164Value: String?,
            rawDigits: String,
        ): ContactLookupOutcome {
            queried += e164Value to rawDigits
            return outcome
        }

        override suspend fun testConnection(): ContactLookupOutcome = outcome
    }

    class FakeStopListDao : StopListDao {
        var keywords: List<StopListEntity> = emptyList()

        override suspend fun getAll(): List<StopListEntity> = keywords
        override fun observeAll(): Flow<List<StopListEntity>> = flowOf(keywords)
        override suspend fun count(): Int = keywords.size
        override suspend fun insert(entity: StopListEntity): Long = throw NotImplementedError()
        override suspend fun delete(entity: StopListEntity): Unit = throw NotImplementedError()
        override suspend fun deleteByKeyword(keyword: String): Int = throw NotImplementedError()
    }

    class FakeOptOutPatternDao : OptOutPatternDao {
        /** Defaults to the same ten rows a fresh database is seeded with. */
        var patterns: List<OptOutPatternEntity> = listOf(
            OptOutPatternEntity(id = 1, pattern = "stop2stop", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 2, pattern = "end2end", replyType = ReplyType.END, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 3, pattern = "stop", replyType = ReplyType.STOP, matchMode = MatchMode.LAST_LINE_EXACT),
            OptOutPatternEntity(id = 4, pattern = "end", replyType = ReplyType.END, matchMode = MatchMode.LAST_LINE_EXACT),
            OptOutPatternEntity(id = 5, pattern = "stop to cancel", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 6, pattern = "stop to opt-out", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 7, pattern = "stop to opt out", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 8, pattern = "stop to end", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 9, pattern = "stop to quit", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(id = 10, pattern = "stop=end", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
        )

        override suspend fun getAll(): List<OptOutPatternEntity> = patterns
        override fun observeAll(): Flow<List<OptOutPatternEntity>> = flowOf(patterns)
        override suspend fun count(): Int = patterns.size
        override suspend fun insert(entity: OptOutPatternEntity): Long = throw NotImplementedError()
        override suspend fun insertAll(entities: List<OptOutPatternEntity>): List<Long> =
            throw NotImplementedError()

        override suspend fun update(pattern: OptOutPatternEntity): Int = throw NotImplementedError()

        override suspend fun delete(entity: OptOutPatternEntity): Unit = throw NotImplementedError()
    }

    class FakeDetectionLogDao : DetectionLogDao {
        /** Every row written, in insertion order. */
        val inserted: MutableList<DetectionLogEntity> = mutableListOf()

        override suspend fun insert(entity: DetectionLogEntity): Long {
            inserted += entity
            return inserted.size.toLong()
        }

        override suspend fun getRecent(limit: Int): List<DetectionLogEntity> =
            inserted.sortedByDescending { it.timestamp }.take(limit)

        override fun observeRecentActionable(
            limit: Int,
            excludedType: LogEventType,
        ): Flow<List<DetectionLogEntity>> = flowOf(inserted.filter { it.eventType != excludedType })

        override fun observeRecentByType(
            eventType: LogEventType,
            limit: Int,
        ): Flow<List<DetectionLogEntity>> = flowOf(inserted.filter { it.eventType == eventType })

        override suspend fun clear(): Int {
            val count = inserted.size
            inserted.clear()
            return count
        }

        override suspend fun count(): Int = inserted.size
    }

    class FakeCooldownDao : AutoReplyCooldownDao {
        /** Sender hash to last-reply timestamp. */
        val rows: MutableMap<String, Long> = mutableMapOf()

        override suspend fun findByHash(senderHash: String): AutoReplyCooldownEntity? =
            rows[senderHash]?.let { AutoReplyCooldownEntity(senderHash, it) }

        override suspend fun isInCooldown(senderHash: String, cutoffTimestamp: Long): Boolean {
            val last = rows[senderHash] ?: return false
            return last >= cutoffTimestamp
        }

        override suspend fun upsert(entity: AutoReplyCooldownEntity) {
            rows[entity.senderHash] = entity.lastReplyTimestamp
        }

        override suspend fun deleteOlderThan(cutoffTimestamp: Long): Int {
            val stale = rows.filterValues { it < cutoffTimestamp }.keys
            stale.forEach(rows::remove)
            return stale.size
        }

        override suspend fun count(): Int = rows.size
    }
}
