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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import com.digiroth.smsfilter.data.db.entity.MessageSource
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import kotlinx.coroutines.flow.first
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

    /**
     * Tests that a newly created database seeds every entry of [AppDatabase.DEFAULT_PATTERNS] via
     * [AppDatabase.SEED_CALLBACK].
     *
     * The expected size is read from [AppDatabase.DEFAULT_PATTERNS] rather than hardcoded. A literal
     * count silently rots every time a pattern is added — these are instrumented tests, so a stale
     * literal does not surface in the JVM unit-test run that gates most commits.
     *
     * Preconditions: Fresh in-memory database instance with seed callback attached.
     * Expected: [OptOutPatternDao.getAll] returns exactly [AppDatabase.DEFAULT_PATTERNS] entries.
     */
    @Test
    fun freshDatabase_seedsAllDefaultOptOutPatterns() = runBlocking {
        val patterns = optOutPatternDao.getAll()

        assertEquals(
            "expected exactly the seeded defaults",
            AppDatabase.DEFAULT_PATTERNS.size,
            patterns.size,
        )
    }

    /**
     * Tests that every pattern declared in [AppDatabase.DEFAULT_PATTERNS] is actually present in a
     * freshly seeded database with its declared reply type and match mode.
     *
     * Preconditions: Fresh in-memory database with the seed callback attached.
     * Expected: Each declared default is found, matched on `(pattern, matchMode)`.
     */
    @Test
    fun freshDatabase_seedsEveryDeclaredDefaultVerbatim() = runBlocking {
        val seeded = optOutPatternDao.getAll().associateBy { it.pattern to it.matchMode }

        AppDatabase.DEFAULT_PATTERNS.forEach { expected ->
            val actual = seeded[expected.pattern to expected.matchMode]
            assertNotNull("'${expected.pattern}' (${expected.matchMode}) should be seeded", actual)
            assertEquals(
                "'${expected.pattern}' seeded with the wrong reply type",
                expected.replyType,
                actual?.replyType,
            )
        }
    }

    /**
     * Tests that the seeded opt-out patterns possess the expected match modes and reply types.
     *
     * Preconditions: Fresh in-memory database with default seeded patterns.
     * Expected: Match modes and reply types match the specification (e.g. stop2stop ANYWHERE STOP, bare stop LAST_LINE_EXACT STOP, etc.).
     */
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

        val stopToCancel = patterns["stop to cancel" to MatchMode.ANYWHERE]
        assertNotNull("stop to cancel should be seeded as ANYWHERE", stopToCancel)
        assertEquals(ReplyType.STOP, stopToCancel?.replyType)

        val stopToOptOutHyphen = patterns["stop to opt-out" to MatchMode.ANYWHERE]
        assertNotNull("stop to opt-out should be seeded as ANYWHERE", stopToOptOutHyphen)
        assertEquals(ReplyType.STOP, stopToOptOutHyphen?.replyType)

        val stopToOptOutSpace = patterns["stop to opt out" to MatchMode.ANYWHERE]
        assertNotNull("stop to opt out should be seeded as ANYWHERE", stopToOptOutSpace)
        assertEquals(ReplyType.STOP, stopToOptOutSpace?.replyType)

        val stopToEnd = patterns["stop to end" to MatchMode.ANYWHERE]
        assertNotNull("stop to end should be seeded as ANYWHERE", stopToEnd)
        assertEquals(ReplyType.STOP, stopToEnd?.replyType)

        val stopToQuit = patterns["stop to quit" to MatchMode.ANYWHERE]
        assertNotNull("stop to quit should be seeded as ANYWHERE", stopToQuit)
        assertEquals(ReplyType.STOP, stopToQuit?.replyType)

        val stopEqualsEnd = patterns["stop=end" to MatchMode.ANYWHERE]
        assertNotNull("stop=end should be seeded as ANYWHERE", stopEqualsEnd)
        assertEquals(ReplyType.STOP, stopEqualsEnd?.replyType)

        val stop2end = patterns["stop2end" to MatchMode.ANYWHERE]
        assertNotNull("stop2end should be seeded as ANYWHERE", stop2end)
        assertEquals(ReplyType.STOP, stop2end?.replyType)

        val stop2quit = patterns["stop2quit" to MatchMode.ANYWHERE]
        assertNotNull("stop2quit should be seeded as ANYWHERE", stop2quit)
        assertEquals(ReplyType.STOP, stop2quit?.replyType)

        val stopToUnsubscribe = patterns["stop to unsubscribe" to MatchMode.ANYWHERE]
        assertNotNull("stop to unsubscribe should be seeded as ANYWHERE", stopToUnsubscribe)
        assertEquals(ReplyType.STOP, stopToUnsubscribe?.replyType)

        val stopToOptout = patterns["stop to optout" to MatchMode.ANYWHERE]
        assertNotNull("stop to optout should be seeded as ANYWHERE", stopToOptout)
        assertEquals(ReplyType.STOP, stopToOptout?.replyType)

        val endToEnd = patterns["end to end" to MatchMode.ANYWHERE]
        assertNotNull("end to end should be seeded as ANYWHERE", endToEnd)
        assertEquals(ReplyType.END, endToEnd?.replyType)

        val end2stop = patterns["end2stop" to MatchMode.ANYWHERE]
        assertNotNull("end2stop should be seeded as ANYWHERE", end2stop)
        assertEquals(ReplyType.END, end2stop?.replyType)
    }

    /**
     * Tests that bare keywords "stop" and "end" are never seeded in ANYWHERE mode.
     *
     * Preconditions: Inspecting all seeded patterns with [MatchMode.ANYWHERE].
     * Expected: Neither bare "stop" nor bare "end" are in the ANYWHERE pattern list.
     */
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

    /**
     * Tests that the stop list table is initially empty in a fresh database.
     *
     * Preconditions: Fresh in-memory database.
     * Expected: [StopListDao.count] returns 0.
     */
    @Test
    fun stopList_startsEmpty() = runBlocking {
        assertEquals(0, stopListDao.count())
    }

    /**
     * Tests inserting and retrieving multiple stop list keyword entities.
     *
     * Preconditions: Inserting "promo" and "newsletter".
     * Expected: [StopListDao.getAll] returns both inserted keywords with count = 2.
     */
    @Test
    fun stopList_insertAndRead() = runBlocking {
        stopListDao.insert(StopListEntity(keyword = "promo"))
        stopListDao.insert(StopListEntity(keyword = "newsletter"))

        val keywords = stopListDao.getAll().map { it.keyword }

        assertEquals(2, keywords.size)
        assertTrue(keywords.contains("promo"))
        assertTrue(keywords.contains("newsletter"))
    }

    /**
     * Tests that attempting to insert a duplicate stop-list keyword is ignored by SQLite conflict resolution.
     *
     * Preconditions: Inserting "promo" twice into StopListDao.
     * Expected: First insert returns rowId > 0, second returns -1L, total count remains 1.
     */
    @Test
    fun stopList_duplicateKeywordIsIgnored() = runBlocking {
        val first = stopListDao.insert(StopListEntity(keyword = "promo"))
        val second = stopListDao.insert(StopListEntity(keyword = "promo"))

        assertTrue("first insert should succeed", first > 0)
        assertEquals("duplicate insert should be ignored", -1L, second)
        assertEquals(1, stopListDao.count())
    }

    /**
     * Tests deleting an existing stop list entry by entity reference.
     *
     * Preconditions: Inserting "promo", then deleting the persisted entity.
     * Expected: [StopListDao.count] becomes 0.
     */
    @Test
    fun stopList_deleteByEntity() = runBlocking {
        stopListDao.insert(StopListEntity(keyword = "promo"))
        val stored = stopListDao.getAll().single()

        stopListDao.delete(stored)

        assertEquals(0, stopListDao.count())
    }

    /**
     * Tests that deleting stop list items by keyword is case-insensitive.
     *
     * Preconditions: Keyword "Promo" stored in database; deleting using uppercase "PROMO".
     * Expected: Delete count is 1 and table count becomes 0.
     */
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

    /**
     * Tests inserting a custom opt-out pattern entity into the database.
     *
     * Preconditions: Seeding has occurred; inserting custom pattern "unsubscribe".
     * Expected: [OptOutPatternDao.count] increments by 1.
     */
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

    /**
     * Tests that the same pattern string can be inserted multiple times if the match mode differs.
     *
     * Preconditions: "stop" is seeded as LAST_LINE_EXACT; inserting "stop" with ANYWHERE mode.
     * Expected: Insert succeeds (rowId > 0) and total count of "stop" patterns becomes 2.
     */
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

    /**
     * Tests that attempting to insert an exact duplicate (same pattern and match mode) is ignored.
     *
     * Preconditions: Inserting "stop2stop" with ANYWHERE mode when it is already seeded.
     * Expected: Insert returns -1L and the pattern count is unchanged from the seeded defaults.
     */
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
        assertEquals(AppDatabase.DEFAULT_PATTERNS.size, optOutPatternDao.count())
    }

    /**
     * Tests deleting an opt-out pattern entity from the database.
     *
     * Preconditions: Seeding completed; deleting "end2end".
     * Expected: Pattern count drops by exactly one and "end2end" is no longer present in getAll.
     */
    @Test
    fun optOutPattern_delete() = runBlocking {
        val target = optOutPatternDao.getAll().first { it.pattern == "end2end" }

        optOutPatternDao.delete(target)

        assertEquals(AppDatabase.DEFAULT_PATTERNS.size - 1, optOutPatternDao.count())
        assertTrue(optOutPatternDao.getAll().none { it.pattern == "end2end" })
    }

    /**
     * Tests updating an existing opt-out pattern entity.
     *
     * Preconditions: Updating existing "end2end" entity with new pattern, reply type, and match mode.
     * Expected: Update returns 1 and retrieved entity contains updated values.
     */
    @Test
    fun optOutPattern_updatesPatternSuccessfully() = runBlocking {
        val target = optOutPatternDao.getAll().first { it.pattern == "end2end" }
        val updated = target.copy(
            pattern = "end2end_custom",
            replyType = ReplyType.STOP,
            matchMode = MatchMode.LAST_LINE_EXACT,
        )

        val count = optOutPatternDao.update(updated)

        assertEquals(1, count)
        val retrieved = optOutPatternDao.getAll().single { it.id == target.id }
        assertEquals("end2end_custom", retrieved.pattern)
        assertEquals(ReplyType.STOP, retrieved.replyType)
        assertEquals(MatchMode.LAST_LINE_EXACT, retrieved.matchMode)
    }

    /**
     * Tests that ReplyType and MatchMode enums round-trip through Room database converters correctly.
     *
     * Preconditions: Inserting pattern with ReplyType.END and MatchMode.LAST_LINE_EXACT.
     * Expected: Read entity preserves exact enum values.
     */
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

    /**
     * Tests that detection log entries are retrieved sorted by timestamp in descending order (newest first).
     *
     * Preconditions: Inserting entries with timestamps 1000L, 3000L, and 2000L.
     * Expected: [DetectionLogDao.getRecent] returns entries ordered [3000L, 2000L, 1000L].
     */
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

    /**
     * Tests that observeAll emits all log event types (DETECTION, IGNORED, NO_MATCH) ordered newest first.
     *
     * Preconditions: Inserting entries across all 3 LogEventType values.
     * Expected: Emitted list contains 3 entries with descending timestamps.
     */
    @Test
    fun detectionLog_observeAllEmitsEveryEntryType() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "stop2stop text",
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
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 3_000L,
                eventType = LogEventType.NO_MATCH,
                messagePreview = "Random promo",
            ),
        )

        val entries = detectionLogDao.observeAll().first()

        assertEquals(3, entries.size)
        val types = entries.map { it.eventType }
        assertTrue(types.contains(LogEventType.DETECTION))
        assertTrue(types.contains(LogEventType.IGNORED))
        assertTrue(types.contains(LogEventType.NO_MATCH))
        assertEquals(3_000L, entries[0].timestamp)
        assertEquals(2_000L, entries[1].timestamp)
        assertEquals(1_000L, entries[2].timestamp)
    }

    /**
     * Tests that DETECTION and IGNORED rows maintain independent field populations (e.g. ignoreReason null on detections).
     *
     * Preconditions: Inserting one DETECTION and one IGNORED log entity.
     * Expected: Detection has matchedPattern and null ignoreReason; Ignored has ignoreReason and null matchedPattern.
     */
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

    /**
     * Tests that query limits in [DetectionLogDao.getRecent] are respected.
     *
     * Preconditions: 10 log entries inserted; querying with limit = 4.
     * Expected: Returns 4 rows containing the newest timestamps.
     */
    @Test
    fun detectionLog_respectsQueryLimit() = runBlocking {
        repeat(10) { index ->
            detectionLogDao.insert(detection(timestamp = index.toLong()))
        }

        val entries = detectionLogDao.getRecent(limit = 4)

        assertEquals(4, entries.size)
        assertEquals("limit should keep the newest rows", 9L, entries.first().timestamp)
    }

    /**
     * Tests that clear deletes all rows from the detection log table.
     *
     * Preconditions: 2 log entries inserted.
     * Expected: [DetectionLogDao.clear] returns 2 and count becomes 0.
     */
    @Test
    fun detectionLog_clearRemovesEveryEntry() = runBlocking {
        detectionLogDao.insert(detection(timestamp = 1_000L))
        detectionLogDao.insert(detection(timestamp = 2_000L))

        val cleared = detectionLogDao.clear()

        assertEquals(2, cleared)
        assertEquals(0, detectionLogDao.count())
    }

    /**
     * Tests persisting and retrieving a non-null sender address in detection log entries.
     *
     * Preconditions: Inserting log entity with senderAddress="+16505551234".
     * Expected: Retrieved entry has senderAddress="+16505551234".
     */
    @Test
    fun detectionLog_persistsAndRetrievesSenderAddress() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "stop2stop this deal",
                senderAddress = "+16505551234",
            ),
        )

        val retrieved = detectionLogDao.getRecent().single()

        assertEquals("+16505551234", retrieved.senderAddress)
    }

    /**
     * Tests that null sender addresses are handled gracefully by Room when reading and writing.
     *
     * Preconditions: Inserting log entity with null senderAddress.
     * Expected: Retrieved entry has null senderAddress.
     */
    @Test
    fun detectionLog_handlesNullSenderAddressGracefully() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "stop2stop this deal",
                senderAddress = null,
            ),
        )

        val retrieved = detectionLogDao.getRecent().single()

        assertNull(retrieved.senderAddress)
    }

    /**
     * Tests persisting and retrieving all MessageSource enum variants (SMS, RCS, MMS).
     *
     * Preconditions: Inserting 3 log entities with SMS, RCS, and MMS message sources respectively.
     * Expected: Retrieved entries preserve their respective MessageSource values.
     */
    @Test
    fun detectionLog_persistsAndRetrievesMessageSource() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "SMS message",
                messageSource = MessageSource.SMS,
            ),
        )
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 2_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "end2end",
                replyStatus = "Reply sent: end",
                messagePreview = "RCS message",
                messageSource = MessageSource.RCS,
            ),
        )
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 3_000L,
                eventType = LogEventType.IGNORED,
                ignoreReason = "Ignored: Known Google Contact",
                messagePreview = "MMS message",
                messageSource = MessageSource.MMS,
            ),
        )

        val entries = detectionLogDao.getRecent().associateBy { it.timestamp }

        assertEquals(MessageSource.SMS, entries[1_000L]?.messageSource)
        assertEquals(MessageSource.RCS, entries[2_000L]?.messageSource)
        assertEquals(MessageSource.MMS, entries[3_000L]?.messageSource)
    }

    /**
     * Tests that the MessageSource property defaults to SMS when unspecified.
     *
     * Preconditions: Inserting log entity using default messageSource parameter.
     * Expected: Retrieved entry has messageSource = [MessageSource.SMS].
     */
    @Test
    fun detectionLog_defaultsMessageSourceToSms() = runBlocking {
        detectionLogDao.insert(
            DetectionLogEntity(
                timestamp = 1_000L,
                eventType = LogEventType.DETECTION,
                matchedPattern = "stop2stop",
                replyStatus = "Reply sent: stop",
                messagePreview = "default message",
            ),
        )

        val retrieved = detectionLogDao.getRecent().single()

        assertEquals(MessageSource.SMS, retrieved.messageSource)
    }

    // ---------------------------------------------------------------------
    // Auto-reply cooldown
    // ---------------------------------------------------------------------

    /**
     * Tests inserting and finding a cooldown entity by its SHA-256 hash.
     *
     * Preconditions: Upserting cooldown record for SENDER_HASH with timestamp 5000L.
     * Expected: [AutoReplyCooldownDao.findByHash] returns entity with lastReplyTimestamp = 5000L.
     */
    @Test
    fun cooldown_upsertAndFind() = runBlocking {
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 5_000L))

        val stored = cooldownDao.findByHash(SENDER_HASH)

        assertNotNull(stored)
        assertEquals(5_000L, stored?.lastReplyTimestamp)
    }

    /**
     * Tests that upsert replaces the timestamp for an existing sender hash without creating duplicate rows.
     *
     * Preconditions: Upserting timestamp 5000L then 9000L for the same SENDER_HASH.
     * Expected: Cooldown row count is 1 and timestamp is updated to 9000L.
     */
    @Test
    fun cooldown_upsertReplacesExistingTimestamp() = runBlocking {
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 5_000L))
        cooldownDao.upsert(AutoReplyCooldownEntity(SENDER_HASH, lastReplyTimestamp = 9_000L))

        assertEquals("upsert must not create a second row", 1, cooldownDao.count())
        assertEquals(9_000L, cooldownDao.findByHash(SENDER_HASH)?.lastReplyTimestamp)
    }

    /**
     * Tests that findByHash returns null for an unrecorded sender hash.
     *
     * Preconditions: Querying an unknown 64-character hash string.
     * Expected: Returns null.
     */
    @Test
    fun cooldown_findByHashReturnsNullForUnknownSender() = runBlocking {
        assertNull(cooldownDao.findByHash("0".repeat(64)))
    }

    /**
     * Tests that a sender with a recent reply is identified as being in cooldown.
     *
     * Preconditions: Reply timestamp was 1 hour ago (within the 24-hour window).
     * Expected: [AutoReplyCooldownDao.isInCooldown] returns true.
     */
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

    /**
     * Tests that a sender with an old reply outside the 24-hour window is not in cooldown.
     *
     * Preconditions: Reply timestamp was 2 days ago.
     * Expected: [AutoReplyCooldownDao.isInCooldown] returns false.
     */
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

    /**
     * Tests that deleteOlderThan deletes only entries with timestamps older than the cutoff.
     *
     * Preconditions: One stale entry older than cutoff and one fresh entry at now.
     * Expected: Stale row is deleted (delete count = 1) and fresh row survives.
     */
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

    // ---------------------------------------------------------------------
    // Migration
    // ---------------------------------------------------------------------

    /**
     * Tests that Database Migration 3->4 seeds the new opt-out patterns without overwriting or duplicating existing ones.
     *
     * Preconditions: SQLite database initialized at version 3 with previous pattern set and one overlapping v4 pattern.
     * Expected: After running [AppDatabase.MIGRATION_3_4], total pattern count is 10 and contains all newly added v4 patterns.
     */
    @Test
    fun migration3To4_seedsNewPatternsWithoutOverwritingExisting() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `${OptOutPatternEntity.TABLE_NAME}` (" +
                                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`pattern` TEXT NOT NULL, " +
                                    "`reply_type` TEXT NOT NULL, " +
                                    "`match_mode` TEXT NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE UNIQUE INDEX IF NOT EXISTS `index_opt_out_patterns_pattern_match_mode` " +
                                    "ON `${OptOutPatternEntity.TABLE_NAME}` (`pattern`, `match_mode`)",
                            )
                            // Seed existing v3 patterns + one existing pattern that overlaps with v4 additions
                            db.execSQL("INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) VALUES ('stop2stop', 'STOP', 'ANYWHERE')")
                            db.execSQL("INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) VALUES ('end2end', 'END', 'ANYWHERE')")
                            db.execSQL("INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) VALUES ('stop', 'STOP', 'LAST_LINE_EXACT')")
                            db.execSQL("INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) VALUES ('end', 'END', 'LAST_LINE_EXACT')")
                            db.execSQL("INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) VALUES ('stop to cancel', 'STOP', 'ANYWHERE')")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    },
                )
                .build(),
        )
        helper.writableDatabase.use { db ->
            AppDatabase.MIGRATION_3_4.migrate(db)

            val cursor = db.query("SELECT pattern, reply_type, match_mode FROM ${OptOutPatternEntity.TABLE_NAME}")
            val patterns = mutableListOf<Triple<String, String, String>>()
            cursor.use {
                while (it.moveToNext()) {
                    patterns.add(Triple(it.getString(0), it.getString(1), it.getString(2)))
                }
            }

            assertEquals(10, patterns.size)
            assertTrue(patterns.contains(Triple("stop to cancel", "STOP", "ANYWHERE")))
            assertTrue(patterns.contains(Triple("stop to opt-out", "STOP", "ANYWHERE")))
            assertTrue(patterns.contains(Triple("stop to opt out", "STOP", "ANYWHERE")))
            assertTrue(patterns.contains(Triple("stop to end", "STOP", "ANYWHERE")))
            assertTrue(patterns.contains(Triple("stop to quit", "STOP", "ANYWHERE")))
            assertTrue(patterns.contains(Triple("stop=end", "STOP", "ANYWHERE")))
        }
    }

    /**
     * Tests that [AppDatabase.MIGRATION_4_5] adds the v5 opt-out patterns to an existing install
     * without disturbing patterns the user already has.
     *
     * This is the migration every already-installed copy of the app actually runs, and it is what
     * delivers `stop2end` — a real message ending in "Stop2End" went unanswered in the field
     * precisely because that pattern was absent before v5.
     *
     * Preconditions: A v4 database holding the ten v4 defaults plus one user-authored custom row.
     * Expected: All six v5 additions are present, the custom row survives untouched, and the total
     *   is the v4 set plus the custom row plus the six additions.
     */
    @Test
    fun migration4To5_addsNewPatternsAndPreservesUserPatterns() {
        openV4Database().writableDatabase.use { db ->
            AppDatabase.MIGRATION_4_5.migrate(db)

            val patterns = readPatterns(db)

            assertTrue("stop2end must be added", patterns.contains(Triple("stop2end", "STOP", "ANYWHERE")))
            assertTrue("stop2quit must be added", patterns.contains(Triple("stop2quit", "STOP", "ANYWHERE")))
            assertTrue("stop to unsubscribe must be added", patterns.contains(Triple("stop to unsubscribe", "STOP", "ANYWHERE")))
            assertTrue("stop to optout must be added", patterns.contains(Triple("stop to optout", "STOP", "ANYWHERE")))
            assertTrue("end to end must be added", patterns.contains(Triple("end to end", "END", "ANYWHERE")))
            assertTrue("end2stop must be added", patterns.contains(Triple("end2stop", "END", "ANYWHERE")))

            assertTrue(
                "a user's own pattern must survive the migration",
                patterns.contains(Triple("my custom optout", "STOP", "ANYWHERE")),
            )

            // 10 v4 defaults + 1 custom + 6 additions.
            assertEquals(17, patterns.size)
        }
    }

    /**
     * Tests that running [AppDatabase.MIGRATION_4_5] twice produces no duplicate rows.
     *
     * The migration guards each insert with `WHERE NOT EXISTS`, so a re-run must be a no-op. Room
     * will not normally invoke a migration twice, but the guard is what makes the same statements
     * safe for a user who already added one of these patterns by hand.
     *
     * Preconditions: A v4 database that has already had the migration applied once.
     * Expected: The second run leaves the pattern count unchanged.
     */
    @Test
    fun migration4To5_isIdempotent() {
        openV4Database().writableDatabase.use { db ->
            AppDatabase.MIGRATION_4_5.migrate(db)
            val afterFirstRun = readPatterns(db)

            AppDatabase.MIGRATION_4_5.migrate(db)
            val afterSecondRun = readPatterns(db)

            assertEquals(
                "re-running the migration must not duplicate rows",
                afterFirstRun.size,
                afterSecondRun.size,
            )
        }
    }

    /**
     * Tests that [AppDatabase.MIGRATION_4_5] does not insert a second copy of a pattern the user
     * had already created themselves.
     *
     * Preconditions: A v4 database in which the user has already added `stop2end` by hand.
     * Expected: Exactly one `stop2end` row exists after the migration.
     */
    @Test
    fun migration4To5_doesNotDuplicateAPatternTheUserAlreadyAdded() {
        openV4Database(
            extraSeed = { db ->
                db.execSQL(
                    "INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) " +
                        "VALUES ('stop2end', 'STOP', 'ANYWHERE')",
                )
            },
        ).writableDatabase.use { db ->
            AppDatabase.MIGRATION_4_5.migrate(db)

            val stop2endRows = readPatterns(db).count { it.first == "stop2end" }
            assertEquals("stop2end must not be duplicated", 1, stop2endRows)
        }
    }

    /**
     * Builds an in-memory database at schema version 4, seeded with the ten v4 default patterns and
     * one user-authored custom pattern.
     *
     * @param extraSeed Optional additional rows to insert during `onCreate`, for tests that need a
     *   pattern to already exist before the migration runs.
     * @return An open helper positioned at version 4, ready for a migration to be applied.
     */
    private fun openV4Database(
        extraSeed: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `${OptOutPatternEntity.TABLE_NAME}` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`pattern` TEXT NOT NULL, " +
                                "`reply_type` TEXT NOT NULL, " +
                                "`match_mode` TEXT NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_opt_out_patterns_pattern_match_mode` " +
                                "ON `${OptOutPatternEntity.TABLE_NAME}` (`pattern`, `match_mode`)",
                        )
                        V4_PATTERNS.forEach { (pattern, replyType, matchMode) ->
                            db.execSQL(
                                "INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) " +
                                    "VALUES ('$pattern', '$replyType', '$matchMode')",
                            )
                        }
                        // A pattern the user added themselves; the migration must leave it alone.
                        db.execSQL(
                            "INSERT INTO ${OptOutPatternEntity.TABLE_NAME} (pattern, reply_type, match_mode) " +
                                "VALUES ('my custom optout', 'STOP', 'ANYWHERE')",
                        )
                        extraSeed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build(),
    )

    /**
     * Reads every opt-out pattern row as a `(pattern, replyType, matchMode)` triple.
     *
     * @param db The database to read from.
     * @return All rows in the opt-out pattern table.
     */
    private fun readPatterns(db: SupportSQLiteDatabase): List<Triple<String, String, String>> {
        val rows = mutableListOf<Triple<String, String, String>>()
        db.query("SELECT pattern, reply_type, match_mode FROM ${OptOutPatternEntity.TABLE_NAME}").use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        return rows
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

        /**
         * The ten opt-out patterns a schema-version-4 database contains, as `(pattern, replyType,
         * matchMode)` triples.
         *
         * Written out literally rather than derived from [AppDatabase.DEFAULT_PATTERNS]: this is a
         * historical snapshot of what shipped in v4, and it must not move when the current defaults
         * change, or the migration test would stop testing the upgrade real users perform.
         */
        val V4_PATTERNS: List<Triple<String, String, String>> = listOf(
            Triple("stop2stop", "STOP", "ANYWHERE"),
            Triple("end2end", "END", "ANYWHERE"),
            Triple("stop", "STOP", "LAST_LINE_EXACT"),
            Triple("end", "END", "LAST_LINE_EXACT"),
            Triple("stop to cancel", "STOP", "ANYWHERE"),
            Triple("stop to opt-out", "STOP", "ANYWHERE"),
            Triple("stop to opt out", "STOP", "ANYWHERE"),
            Triple("stop to end", "STOP", "ANYWHERE"),
            Triple("stop to quit", "STOP", "ANYWHERE"),
            Triple("stop=end", "STOP", "ANYWHERE"),
        )
    }
}
