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

package com.digiroth.smsfilter.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented CRUD coverage for every entity in [AppDatabase], plus verification that the
 * opt-out pattern seeding callback runs on a freshly created database.
 *
 * Uses `runBlocking` rather than `runTest`: `kotlinx-coroutines-test` is declared only for the
 * JVM unit-test source set, and DAO calls here are genuinely blocking database work with no
 * virtual time to control, so there is nothing `runTest` would add.
 *
 * The in-memory database is built with [AppDatabase.SEED_CALLBACK] attached, because Room
 * invokes creation callbacks only through the builder — omitting it here would let the seeding
 * assertions pass against production code that never seeds.
 */
@RunWith(AndroidJUnit4::class)
class RoomDatabaseTest {

    private lateinit var database: AppDatabase
    private lateinit var stopListDao: StopListDao
    private lateinit var optOutPatternDao: OptOutPatternDao
    private lateinit var detectionLogDao: DetectionLogDao
    private lateinit var cooldownDao: AutoReplyCooldownDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .addCallback(AppDatabase.SEED_CALLBACK)
            .allowMainThreadQueries()
            .build()

        stopListDao = database.stopListDao()
        optOutPatternDao = database.optOutPatternDao()
        detectionLogDao = database.detectionLogDao()
        cooldownDao = database.autoReplyCooldownDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDatabase() {
        database.close()
    }

    // ---------------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------------

    @Test
    fun freshDatabase_seedsFourDefaultOptOutPatterns() = runBlocking {
        val patterns = optOutPatternDao.getAll()

        assertEquals("expected exactly the four seeded defaults", 4, patterns.size)
    }

    @Test
    fun seededPatterns_haveCorrectMatchModeAndReplyType() = runBlocking {
        val patterns = optOutPatternDao.getAll().associateBy { it.pattern to it.matchMode }

        val stop2stop = patterns["stop2stop" to MatchMode.ANYWHERE]
        assertNotNull("stop2stop should be seeded as ANYWHERE", stop2stop)
        assertEquals(ReplyType.STOP, stop2stop?.replyType)

        val end2end = patterns["end2end" to MatchMode.ANYWHERE]
        assertNotNull("end2end should be seeded as ANYWHERE", end2end)
        assertEquals(ReplyType.END, end2end?.replyType)

        val stop = patterns["stop" to MatchMode.LAST_LINE_EXACT]
        assertNotNull("bare stop should be seeded as LAST_LINE_EXACT", stop)
        assertEquals(ReplyType.STOP, stop?.replyType)

        val end = patterns["end" to MatchMode.LAST_LINE_EXACT]
        assertNotNull("bare end should be seeded as LAST_LINE_EXACT", end)
        assertEquals(ReplyType.END, end?.replyType)
    }

    @Test
    fun seededPatterns_doNotIncludeBareKeywordsAsAnywhere() = runBlocking {
        // Guards the single most consequential seeding mistake: a bare "stop" matching
        // anywhere would fire on ordinary marketing copy such as "reply STOP to unsubscribe".
        val anywherePatterns = optOutPatternDao.getAll()
            .filter { it.matchMode == MatchMode.ANYWHERE }
            .map { it.pattern }

        assertFalse("bare 'stop' must not match anywhere", anywherePatterns.contains("stop"))
        assertFalse("bare 'end' must not match anywhere", anywherePatterns.contains("end"))
    }

    // ---------------------------------------------------------------------
    // Stop list
    // ---------------------------------------------------------------------

    @Test
    fun stopList_startsEmpty() = runBlocking {
        assertEquals(0, stopListDao.count())
    }

    @Test
    fun stopList_insertAndRead() = runBlocking {
        stopListDao.insert(StopListEntity(keyword = "promo"))
        stopListDao.insert(StopListEntity(keyword = "newsletter"))

        val keywords = stopListDao.getAll().map { it.keyword }

        assertEquals(2, keywords.size)
        assertTrue(keywords.contains("promo"))
        assertTrue(keywords.contains("newsletter"))
    }

    @Test
    fun stopList_duplicateKeywordIsIgnored() = runBlocking {
        val first = stopListDao.insert(StopListEntity(keyword = "promo"))
        val second = stopListDao.insert(StopListEntity(keyword = "promo"))

        assertTrue("first insert should succeed", first > 0)
        assertEquals("duplicate insert should be ignored", -1L, second)
        assertEquals(1, stopListDao.count())
    }

    @Test
    fun stopList_deleteByEntity() = runBlocking {
        stopListDao.insert(StopListEntity(keyword = "promo"))
        val stored = stopListDao.getAll().single()

        stopListDao.delete(stored)

        assertEquals(0, stopListDao.count())
    }

    @Test
    fun stopList_deleteByKeywordIsCaseInsensitive() = runBlocking {
        stopListDao.insert(StopListEntity(keyword = "Promo"))

        val deleted = stopListDao.deleteByKeyword("PROMO")

        assertEquals(1, deleted)
        assertEquals(0, stopListDao.count())
    }

    // ---------------------------------------------------------------------
    // Opt-out patterns
    // ---------------------------------------------------------------------

    @Test
    fun optOutPattern_insertCustomPattern() = runBlocking {
        val seededCount = optOutPatternDao.count()

        optOutPatternDao.insert(
            OptOutPatternEntity(
                pattern = "unsubscribe",
                replyType = ReplyType.STOP,
                matchMode = MatchMode.ANYWHERE,
            ),
        )

        assertEquals(seededCount + 1, optOutPatternDao.count())
    }

    @Test
    fun optOutPattern_samePatternWithDifferentMatchModeIsAllowed() = runBlocking {
        // "stop" is seeded as LAST_LINE_EXACT; adding it as ANYWHERE is a distinct rule and
        // must be permitted, since the unique index covers (pattern, match_mode) together.
        val inserted = optOutPatternDao.insert(
            OptOutPatternEntity(
                pattern = "stop",
                replyType = ReplyType.STOP,
                matchMode = MatchMode.ANYWHERE,
            ),
        )

        assertTrue("distinct match mode should insert", inserted > 0)
        assertEquals(2, optOutPatternDao.getAll().count { it.pattern == "stop" })
    }

    @Test
    fun optOutPattern_exactDuplicateIsIgnored() = runBlocking {
        val duplicate = optOutPatternDao.insert(
            OptOutPatternEntity(
                pattern = "stop2stop",
                replyType = ReplyType.STOP,
                matchMode = MatchMode.ANYWHERE,
            ),
        )

        assertEquals("exact duplicate should be ignored", -1L, duplicate)
        assertEquals(4, optOutPatternDao.count())
    }

    @Test
    fun optOutPattern_delete() = runBlocking {
        val target = optOutPatternDao.getAll().first { it.pattern == "end2end" }

        optOutPatternDao.delete(target)

        assertEquals(3, optOutPatternDao.count())
        assertTrue(optOutPatternDao.getAll().none { it.pattern == "end2end" })
    }

    @Test
    fun optOutPattern_enumRoundTripsThroughConverters() = runBlocking {
        optOutPatternDao.insert(
            OptOutPatternEntity(
                pattern = "quit",
                replyType = ReplyType.END,
                matchMode = MatchMode.LAST_LINE_EXACT,
            ),
        )

        val stored = optOutPatternDao.getAll().single { it.pattern == "quit" }

        assertEquals(ReplyType.END, stored.replyType)
        assertEquals(MatchMode.LAST_LINE_EXACT, stored.matchMode)
    }

    // ---------------------------------------------------------------------
    // Detection log
    // ---------------------------------------------------------------------

    @Test
    fun detectionLog_insertAndReadNewestFirst() = runBlocking {
        detectionLogDao.insert(detection(timestamp = 1_000L, pattern = "stop2stop"))
        detectionLogDao.insert(detection(timestamp = 3_000L, pattern = "end2end"))
        detectionLogDao.insert(detection(timestamp = 2_000L, pattern = "stop"))

        val entries = detectionLogDao.getRecent()

        assertEquals(3, entries.size)
        assertEquals("newest entry should sort first", 3_000L, entries.first().timestamp)
        assertEquals("oldest entry should sort last", 1_000L, entries.last().timestamp)
    }

    @Test
    fun detectionLog_storesDetectionAndIgnoredFieldsIndependently() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "stop2stop this deal",
            ),
        )
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 2_000L,
                eventType = LogEventType.IGNORED,
                ignoreReason = "Ignored: Known Google Contact",
                messagePreview = "Lunch at noon?",
            ),
        )

        val entries = detectionLogDao.getRecent().associateBy { it.eventType }

        val detection = entries.getValue(LogEventType.DETECTION)
        assertEquals("stop2stop", detection.matchedPattern)
        assertEquals("Reply sent: stop", detection.replyStatus)
        assertNull("a detection carries no ignore reason", detection.ignoreReason)

        val ignored = entries.getValue(LogEventType.IGNORED)
        assertEquals("Ignored: Known Google Contact", ignored.ignoreReason)
        assertNull("an ignored event carries no matched pattern", ignored.matchedPattern)
        assertNull("an ignored event carries no reply status", ignored.replyStatus)
    }

    @Test
    fun detectionLog_respectsQueryLimit() = runBlocking {
        repeat(10) { index ->
            detectionLogDao.insert(detection(timestamp = index.toLong()))
        }

        val entries = detectionLogDao.getRecent(limit = 4)

        assertEquals(4, entries.size)
        assertEquals("limit should keep the newest rows", 9L, entries.first().timestamp)
    }

    @Test
    fun detectionLog_clearRemovesEveryEntry() = runBlocking {
        detectionLogDao.insert(detection(timestamp = 1_000L))
        detectionLogDao.insert(detection(timestamp = 2_000L))

        val cleared = detectionLogDao.clear()

        assertEquals(2, cleared)
        assertEquals(0, detectionLogDao.count())
    }

    // ---------------------------------------------------------------------
    // Auto-reply cooldown
    // ---------------------------------------------------------------------

    @Test
    fun cooldown_upsertAndFind() = runBlocking {
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 5_000L))

        val stored = cooldownDao.findByHash(SENDER_HASH)

        assertNotNull(stored)
        assertEquals(5_000L, stored?.lastReplyTimestamp)
    }

    @Test
    fun cooldown_upsertReplacesExistingTimestamp() = runBlocking {
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 5_000L))
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 9_000L))

        assertEquals("upsert must not create a second row", 1, cooldownDao.count())
        assertEquals(9_000L, cooldownDao.findByHash(SENDER_HASH)?.lastReplyTimestamp)
    }

    @Test
    fun cooldown_findByHashReturnsNullForUnknownSender() = runBlocking {
        assertNull(cooldownDao.findByHash("0".repeat(64)))
    }

    @Test
    fun cooldown_recentReplyIsInsideWindow() = runBlocking {
        val now = 100_000_000L
        val oneHourAgo = now - (60L * 60L * 1000L)
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = oneHourAgo))

        val inCooldown = cooldownDao.isInCooldown(
            senderHash = SENDER_HASH,
            cutoffTimestamp = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS,
        )

        assertTrue("a reply one hour ago is still inside the 24-hour window", inCooldown)
    }

    @Test
    fun cooldown_oldReplyIsOutsideWindow() = runBlocking {
        val now = 100_000_000L
        val twoDaysAgo = now - (2L * AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS)
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = twoDaysAgo))

        val inCooldown = cooldownDao.isInCooldown(
            senderHash = SENDER_HASH,
            cutoffTimestamp = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS,
        )

        assertFalse("a reply two days ago must not block a new reply", inCooldown)
    }

    @Test
    fun cooldown_deleteOlderThanPrunesOnlyStaleRows() = runBlocking {
        val now = 100_000_000L
        val cutoff = now - AutoReplyCooldownEntity.COOLDOWN_WINDOW_MS
        cooldownDao.upsert(AutoReplyCooldownEntity("a".repeat(64), lastReplyTimestamp = cutoff - 1))
        cooldownDao.upsert(AutoReplyCooldownEntity("b".repeat(64), lastReplyTimestamp = now))

        val deleted = cooldownDao.deleteOlderThan(cutoff)

        assertEquals(1, deleted)
        assertEquals(1, cooldownDao.count())
        assertNotNull("the recent row must survive", cooldownDao.findByHash("b".repeat(64)))
    }

    private fun detection(
        timestamp: Long,
        pattern: String = "stop2stop",
    ): DetectionLogEntity = DetectionLogEntity(
        timestamp = timestamp,
        eventType = LogEventType.DETECTION,
        matchedPattern = pattern,
        replyStatus = "Reply sent: stop",
        messagePreview = "preview text",
    )

    private companion object {
        /** A representative lowercase-hex SHA-256 hash; contents are arbitrary for these tests. */
        const val SENDER_HASH = "3b1f8c2d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e"
    }
}
