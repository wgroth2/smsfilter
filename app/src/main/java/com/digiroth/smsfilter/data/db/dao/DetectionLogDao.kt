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

package com.digiroth.smsfilter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the activity and detection log.
 *
 * For official Android documentation on Room DAO patterns and database access, see:
 * - Room Migrations & Database Architecture: [https://developer.android.com/training/data-storage/room/migrating-db-versions](https://developer.android.com/training/data-storage/room/migrating-db-versions)
 *
 * The log screen shows at most [DetectionLogEntity.MAX_DISPLAYED_ENTRIES] rows, enforced
 * here as a query `LIMIT` rather than by deleting older rows, so history survives for
 * debugging even though the UI shows only the most recent window.
 */
@Dao
interface DetectionLogDao {

    /**
     * Observes every log entry regardless of event type, newest first.
     *
     * Backs the "All" filter chip. Emits all message evaluations received by the app,
     * including detections, ignored messages, and non-matching texts.
     *
     * @param limit Maximum rows to emit.
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM ${DetectionLogEntity.TABLE_NAME} " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun observeAll(
        limit: Int = DetectionLogEntity.MAX_DISPLAYED_ENTRIES,
    ): Flow<List<DetectionLogEntity>>

    /**
     * Observes the most recent entries the app actually acted on, newest first: detections and
     * ignored messages, but not [LogEventType.NO_MATCH].
     *
     * Backs the default "All" chip. Unmatched messages are excluded because there is one of them
     * for every non-matching text the device receives, which would swamp the capped display window
     * and bury the rows the user came to the log to find. They remain available under their own
     * chip via [observeRecentByType].
     *
     * @param limit Maximum rows to emit.
     * @param excludedType The event type to leave out. Bound as a parameter rather than written as
     *   a SQL literal so the query cannot drift from the enum constant's name.
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM ${DetectionLogEntity.TABLE_NAME} WHERE event_type != :excludedType " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun observeRecentActionable(
        limit: Int = DetectionLogEntity.MAX_DISPLAYED_ENTRIES,
        excludedType: LogEventType = LogEventType.NO_MATCH,
    ): Flow<List<DetectionLogEntity>>

    /**
     * Observes the most recent log entries of a single kind, newest first. Backs the
     * "Detections Only", "Ignored Only" and "Not Matched" filter chips.
     *
     * @param eventType Which kind of entry to include.
     * @param limit Maximum rows to emit.
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM ${DetectionLogEntity.TABLE_NAME} WHERE event_type = :eventType " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun observeRecentByType(
        eventType: LogEventType,
        limit: Int = DetectionLogEntity.MAX_DISPLAYED_ENTRIES,
    ): Flow<List<DetectionLogEntity>>

    /**
     * Reads the most recent log entries once.
     *
     * @param limit Maximum rows to return.
     * @return The newest entries, newest first.
     */
    @Query(
        "SELECT * FROM ${DetectionLogEntity.TABLE_NAME} " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecent(limit: Int = DetectionLogEntity.MAX_DISPLAYED_ENTRIES): List<DetectionLogEntity>

    /**
     * Appends a log entry.
     *
     * @param entity The entry to record.
     * @return The new row id.
     */
    @Insert
    suspend fun insert(entity: DetectionLogEntity): Long

    /**
     * Deletes every log entry. Backs the "Clear Log" button.
     *
     * @return The number of rows deleted.
     */
    @Query("DELETE FROM ${DetectionLogEntity.TABLE_NAME}")
    suspend fun clear(): Int

    /**
     * Counts log entries.
     *
     * @return The number of rows in the table.
     */
    @Query("SELECT COUNT(*) FROM ${DetectionLogEntity.TABLE_NAME}")
    suspend fun count(): Int
}
