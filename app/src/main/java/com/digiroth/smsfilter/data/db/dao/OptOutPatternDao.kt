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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for opt-out patterns.
 *
 * `SmsLookupWorker` reads the live list via [getAll] and passes it to `OptOutDetector`; the
 * detector itself never touches this DAO, which keeps it a pure, database-free class that
 * can be unit-tested on the JVM.
 */
@Dao
interface OptOutPatternDao {

    /**
     * Observes every configured pattern.
     *
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query("SELECT * FROM ${OptOutPatternEntity.TABLE_NAME} ORDER BY pattern COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<OptOutPatternEntity>>

    /**
     * Reads every configured pattern once.
     *
     * @return All stored patterns, including the seeded defaults unless the user deleted them.
     */
    @Query("SELECT * FROM ${OptOutPatternEntity.TABLE_NAME} ORDER BY pattern COLLATE NOCASE ASC")
    suspend fun getAll(): List<OptOutPatternEntity>

    /**
     * Inserts a pattern, ignoring the insert if the same pattern already exists with the
     * same match mode.
     *
     * @param entity The pattern to add.
     * @return The new row id, or `-1` if the insert was ignored as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: OptOutPatternEntity): Long

    /**
     * Inserts several patterns, ignoring any that already exist.
     *
     * @param entities The patterns to add.
     * @return The new row ids, with `-1` for each ignored duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<OptOutPatternEntity>): List<Long>

    /**
     * Updates an existing pattern.
     *
     * @param pattern The pattern entity containing updated values, matched by primary key.
     * @return The number of rows updated (1 if found, 0 otherwise).
     */
    @Update
    suspend fun update(pattern: OptOutPatternEntity): Int

    /**
     * Deletes a pattern.
     *
     * @param entity The row to remove; matched by primary key.
     */
    @Delete
    suspend fun delete(entity: OptOutPatternEntity)

    /**
     * Counts stored patterns.
     *
     * @return The number of rows in the table.
     */
    @Query("SELECT COUNT(*) FROM ${OptOutPatternEntity.TABLE_NAME}")
    suspend fun count(): Int
}
