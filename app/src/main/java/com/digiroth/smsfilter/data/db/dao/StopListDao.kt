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
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for stop-list keywords.
 *
 * Two read shapes are offered deliberately: [observeAll] backs the Settings UI reactively,
 * while [getAll] is a one-shot suspending read for `SmsLookupWorker`, which needs a
 * snapshot of the list at the moment a message arrives rather than a subscription.
 */
@Dao
interface StopListDao {

    /**
     * Observes every stop-list keyword, alphabetically ordered.
     *
     * @return A [Flow] that re-emits whenever the table changes.
     */
    @Query("SELECT * FROM ${StopListEntity.TABLE_NAME} ORDER BY keyword COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StopListEntity>>

    /**
     * Reads every stop-list keyword once.
     *
     * @return All stored keywords; empty if the user has not added any.
     */
    @Query("SELECT * FROM ${StopListEntity.TABLE_NAME} ORDER BY keyword COLLATE NOCASE ASC")
    suspend fun getAll(): List<StopListEntity>

    /**
     * Inserts a keyword, ignoring the insert if the keyword already exists.
     *
     * @param entity The keyword to add.
     * @return The new row id, or `-1` if the insert was ignored as a duplicate.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StopListEntity): Long

    /**
     * Deletes a keyword.
     *
     * @param entity The row to remove; matched by primary key.
     */
    @Delete
    suspend fun delete(entity: StopListEntity)

    /**
     * Deletes a keyword by its text, case-insensitively.
     *
     * @param keyword The keyword text to remove.
     * @return The number of rows deleted (0 or 1).
     */
    @Query("DELETE FROM ${StopListEntity.TABLE_NAME} WHERE keyword = :keyword COLLATE NOCASE")
    suspend fun deleteByKeyword(keyword: String): Int

    /**
     * Counts stored keywords.
     *
     * @return The number of rows in the table.
     */
    @Query("SELECT COUNT(*) FROM ${StopListEntity.TABLE_NAME}")
    suspend fun count(): Int
}
