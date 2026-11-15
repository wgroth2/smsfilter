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

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the schema-version-6 stop-list options: the 5 → 6 upgrade, and the new
 * update and exact-lookup DAO methods.
 *
 * The upgrade test is end to end. It writes a real database *file* using the exact version-5 schema
 * Room produced on shipped installs, then opens it with `Room.databaseBuilder` and
 * [AppDatabase.ALL_MIGRATIONS] — the same path the app takes on its first launch after an update.
 * Room runs the migration and then validates the resulting schema against the v6 entities, so a
 * column definition that drifted from [StopListEntity] fails here rather than on a user's phone.
 *
 * The test database uses its own file name and is deleted before and after each test, so it can
 * never touch the app's real `smsfilter.db`.
 */
@RunWith(AndroidJUnit4::class)
class StopListMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteLeftovers() {
        context.deleteDatabase(TEST_DB_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB_NAME)
    }

    /**
     * Tests that upgrading a populated v5 database keeps every row and gives existing keywords the
     * pre-v6 behaviour.
     *
     * Preconditions: A v5 file database holding three stop words (including two that differ only
     * in case), two log rows, one custom opt-out pattern, and one cooldown record.
     * Expected: Room opens it at v6 without a schema-validation error; every row survives; each
     * existing keyword has both options off; and the new columns accept an edit.
     */
    @Test
    fun migration5To6_preservesAllDataAndDefaultsOptionsOff() = runBlocking {
        createV5Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB_NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val keywords = database.stopListDao().getAll()
            assertEquals(listOf("Avis", "avis", "PG&E").sorted(), keywords.map { it.keyword }.sorted())
            assertTrue("existing keywords must stay case-insensitive", keywords.none { it.caseSensitive })
            assertTrue("existing keywords must stay substring matches", keywords.none { it.wholeWord })

            assertEquals("log rows must survive", 2, database.detectionLogDao().count())
            assertEquals("patterns must survive", 1, database.optOutPatternDao().count())
            assertTrue(
                "cooldown must survive",
                database.autoReplyCooldownDao().isInCooldown(COOLDOWN_HASH, cutoffTimestamp = 0L),
            )

            val avis = keywords.single { it.keyword == "Avis" }
            database.stopListDao().update(avis.copy(wholeWord = true))
            val reloaded = database.stopListDao().findByKeyword("Avis")
            assertEquals(avis.copy(wholeWord = true), reloaded)
        } finally {
            database.close()
        }
    }

    /**
     * Tests that the migration only adds columns: the stop list's ids are unchanged.
     *
     * Preconditions: A v5 database whose stop words have known ids.
     * Expected: The same ids after the upgrade, proving no table was rebuilt or renumbered.
     */
    @Test
    fun migration5To6_keepsRowIds() = runBlocking {
        createV5Database()

        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB_NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val idsByKeyword = database.stopListDao().getAll().associate { it.keyword to it.id }
            assertEquals(mapOf("Avis" to 10L, "avis" to 11L, "PG&E" to 12L), idsByKeyword)
        } finally {
            database.close()
        }
    }

    /**
     * Tests the update and exact-lookup DAO methods on a fresh v6 database.
     *
     * Preconditions: An in-memory database with "Avis" and "avis".
     * Expected: findByKeyword is case-sensitive; update changes the options; renaming one entry to
     * the other's exact text violates the unique index.
     */
    @Test
    fun stopList_updateAndExactLookup() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.stopListDao()
            assertTrue(dao.insert(StopListEntity(keyword = "Avis")) > 0)
            assertTrue("case variants are separate entries", dao.insert(StopListEntity(keyword = "avis")) > 0)

            val upper = assertNotNullAndGet(dao.findByKeyword("Avis"))
            assertEquals("Avis", upper.keyword)
            assertNull("lookup must be exact", dao.findByKeyword("AVIS"))

            assertEquals(1, dao.update(upper.copy(caseSensitive = true, wholeWord = true)))
            val updated = assertNotNullAndGet(dao.findByKeyword("Avis"))
            assertTrue(updated.caseSensitive)
            assertTrue(updated.wholeWord)

            val lower = assertNotNullAndGet(dao.findByKeyword("avis"))
            assertFalse(lower.wholeWord)
            try {
                dao.update(lower.copy(keyword = "Avis"))
                fail("renaming to an existing keyword must violate the unique index")
            } catch (expected: SQLiteConstraintException) {
                // The ViewModel maps this exception to a "duplicate" error.
                assertNotNull(expected)
            }
        } finally {
            database.close()
        }
    }

    /**
     * Asserts a value is non-null and returns it, for readable chained assertions.
     *
     * @param value The value to check.
     * @return The non-null value.
     */
    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /**
     * Writes a v5 database file using the exact schema Room created on shipped v5 installs, copied
     * from a real device, and seeds it with representative rows.
     */
    private fun createV5Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            V5_SCHEMA.forEach(db::execSQL)
                            db.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'v5-identity')")
                            db.execSQL("INSERT INTO stop_list (id, keyword) VALUES (10, 'Avis'), (11, 'avis'), (12, 'PG&E')")
                            db.execSQL(
                                "INSERT INTO detection_log (timestamp, event_type, ignore_reason, message_preview, sender_address) " +
                                    "VALUES (1000, 'IGNORED', 'Ignored: Matched Stop List word ''avis''', 'Call Davis', '5551234')",
                            )
                            db.execSQL(
                                "INSERT INTO detection_log (timestamp, event_type, matched_pattern, reply_status, message_preview, message_source) " +
                                    "VALUES (2000, 'DETECTION', 'stop2end', 'Reply sent: stop', 'Stop2End', 'RCS')",
                            )
                            db.execSQL(
                                "INSERT INTO opt_out_patterns (pattern, reply_type, match_mode) " +
                                    "VALUES ('my custom optout', 'STOP', 'LAST_LINE_CONTAINS')",
                            )
                            db.execSQL(
                                "INSERT INTO auto_reply_cooldown (sender_hash, last_reply_timestamp) VALUES ('$COOLDOWN_HASH', 5000)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        // Opening forces onCreate; closing flushes the file so Room opens it fresh.
        helper.writableDatabase.close()
        helper.close()
    }

    private companion object {
        /** File name for the test database; distinct from the app's real `smsfilter.db`. */
        const val TEST_DB_NAME: String = "stop-list-migration-test.db"

        /** Arbitrary cooldown sender hash seeded into the v5 database. */
        const val COOLDOWN_HASH: String = "migration-test-sender-hash"

        /**
         * The schema-version-5 tables and indices, verbatim from a device running v5.
         *
         * A historical snapshot: it must not change when the current entities change, or this test
         * would stop exercising the upgrade real users perform.
         */
        val V5_SCHEMA: List<String> = listOf(
            "CREATE TABLE `stop_list` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `keyword` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX `index_stop_list_keyword` ON `stop_list` (`keyword`)",
            "CREATE TABLE `opt_out_patterns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pattern` TEXT NOT NULL, " +
                "`reply_type` TEXT NOT NULL, `match_mode` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX `index_opt_out_patterns_pattern_match_mode` ON `opt_out_patterns` (`pattern`, `match_mode`)",
            "CREATE TABLE `detection_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`event_type` TEXT NOT NULL, `matched_pattern` TEXT, `reply_status` TEXT, `ignore_reason` TEXT, " +
                "`message_preview` TEXT NOT NULL, `sender_address` TEXT, `message_source` TEXT NOT NULL DEFAULT 'SMS')",
            "CREATE INDEX `index_detection_log_timestamp` ON `detection_log` (`timestamp`)",
            "CREATE INDEX `index_detection_log_event_type` ON `detection_log` (`event_type`)",
            "CREATE TABLE `auto_reply_cooldown` (`sender_hash` TEXT NOT NULL, `last_reply_timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sender_hash`))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        )
    }
}
