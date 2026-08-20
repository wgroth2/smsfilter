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

import android.util.Log
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.digiroth.smsfilter.data.db.dao.AutoReplyCooldownDao
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.AutoReplyCooldownEntity
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity

/**
 * The application's Room database.
 *
 * Holds only list-shaped data: stop-list keywords, opt-out patterns, the activity log, and
 * auto-reply cooldown records. **All scalar settings and flags live in
 * `DataStore<Preferences>` instead — there is deliberately no settings table here.**
 *
 * The cooldown table holds one-way SHA-256 hashes of sender addresses, which cannot be
 * reversed into a number.
 *
 * `exportSchema` is `false` because the project uses selective migrations and ships no
 * migration scripts, so an exported schema would have nothing to validate against. This also
 * suppresses Room's "schema export directory was not provided" warning without needing a KSP
 * argument in the build file.
 */
@Database(
    entities = [
        StopListEntity::class,
        OptOutPatternEntity::class,
        DetectionLogEntity::class,
        AutoReplyCooldownEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {

    /** @return Data access for stop-list keywords. */
    abstract fun stopListDao(): StopListDao

    /** @return Data access for opt-out patterns. */
    abstract fun optOutPatternDao(): OptOutPatternDao

    /** @return Data access for the activity and detection log. */
    abstract fun detectionLogDao(): DetectionLogDao

    /** @return Data access for auto-reply cooldown records. */
    abstract fun autoReplyCooldownDao(): AutoReplyCooldownDao

    companion object {
        /** Logging tag for database-level events. */
        private const val TAG = "AppDatabase"

        /** On-disk database file name. */
        const val DATABASE_NAME: String = "smsfilter.db"

        /**
         * The four opt-out patterns seeded into a freshly created database.
         *
         * `stop2stop` and `end2end` match anywhere because they are distinctive enough that a
         * substring hit is unambiguous. Bare `stop` and `end` are last-line-exact only —
         * matching them anywhere would fire on ordinary marketing copy such as
         * "reply STOP to unsubscribe", producing a false positive on nearly every message.
         */
        val DEFAULT_PATTERNS: List<OptOutPatternEntity> = listOf(
            OptOutPatternEntity(pattern = "stop2stop", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(pattern = "end2end", replyType = ReplyType.END, matchMode = MatchMode.ANYWHERE),
            OptOutPatternEntity(pattern = "stop", replyType = ReplyType.STOP, matchMode = MatchMode.LAST_LINE_EXACT),
            OptOutPatternEntity(pattern = "end", replyType = ReplyType.END, matchMode = MatchMode.LAST_LINE_EXACT),
        )

        /**
         * Migrates the database from version 1 to version 2 by adding the nullable `sender_address`
         * column to the detection log table.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detection_log ADD COLUMN sender_address TEXT")
            }
        }

        /**
         * Migrates the database from version 2 to version 3 by adding the `message_source`
         * column with a default value of `'SMS'` to the detection log table.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ${DetectionLogEntity.TABLE_NAME} ADD COLUMN message_source TEXT DEFAULT 'SMS'")
            }
        }

        /**
         * Seeds [DEFAULT_PATTERNS] when the database file is first created.
         *
         * The insert is raw SQL rather than a DAO call because `onCreate` runs while the
         * database is still being opened, so the generated DAOs are not yet usable. The enum
         * values written here are the same `name` strings [RoomConverters] produces, and the
         * column names match [OptOutPatternEntity]; if either changes, this SQL must change
         * with it.
         *
         * This callback must be attached wherever the database is built — including in tests
         * that assert on seeding — since Room invokes it only through the builder.
         */
        val SEED_CALLBACK: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                Log.d(TAG, "Fresh database created; seeding ${DEFAULT_PATTERNS.size} opt-out patterns")
                runCatching {
                    DEFAULT_PATTERNS.forEach { pattern ->
                        db.execSQL(
                            "INSERT INTO ${OptOutPatternEntity.TABLE_NAME} " +
                                "(pattern, reply_type, match_mode) VALUES (?, ?, ?)",
                            arrayOf(pattern.pattern, pattern.replyType.name, pattern.matchMode.name),
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to seed default opt-out patterns", error)
                }
            }
        }
    }
}
