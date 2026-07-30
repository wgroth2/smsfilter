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
import androidx.room.Query
import androidx.room.Upsert
import com.digiroth.smsfilter.data.db.entity.AutoReplyCooldownEntity

/**
 * Data access for the 24-hour auto-reply cooldown table.
 *
 * Every method is keyed by the SHA-256 hash of the raw sender address — the plain address is
 * never passed to or stored by this DAO.
 */
@Dao
interface AutoReplyCooldownDao {

    /**
     * Looks up the cooldown record for one sender.
     *
     * @param senderHash Lowercase-hex SHA-256 hash of the raw originating address.
     * @return The record, or `null` if this sender has never been auto-replied to (or its
     *   record has since been pruned).
     */
    @Query(
        "SELECT * FROM ${AutoReplyCooldownEntity.TABLE_NAME} WHERE sender_hash = :senderHash LIMIT 1",
    )
    suspend fun findByHash(senderHash: String): AutoReplyCooldownEntity?

    /**
     * Determines whether a sender is still inside the cooldown window.
     *
     * Expressed as a single query rather than a fetch-then-compare so the decision cannot
     * drift from the stored data between the two steps.
     *
     * @param senderHash Lowercase-hex SHA-256 hash of the raw originating address.
     * @param cutoffTimestamp Epoch milliseconds; a reply sent at or after this instant still
     *   blocks a new reply. Callers pass `now - COOLDOWN_WINDOW_MS`.
     * @return `true` if an auto-reply was already sent to this sender at or after
     *   [cutoffTimestamp].
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM ${AutoReplyCooldownEntity.TABLE_NAME} " +
            "WHERE sender_hash = :senderHash AND last_reply_timestamp >= :cutoffTimestamp)",
    )
    suspend fun isInCooldown(senderHash: String, cutoffTimestamp: Long): Boolean

    /**
     * Records that an auto-reply was just sent, inserting or replacing the sender's record.
     *
     * @param entity The cooldown record to store.
     */
    @Upsert
    suspend fun upsert(entity: AutoReplyCooldownEntity)

    /**
     * Prunes records whose last reply is older than the cooldown window. Run as housekeeping
     * on every worker execution so the table cannot grow without bound.
     *
     * @param cutoffTimestamp Epoch milliseconds; records strictly older than this are deleted.
     * @return The number of rows deleted.
     */
    @Query(
        "DELETE FROM ${AutoReplyCooldownEntity.TABLE_NAME} WHERE last_reply_timestamp < :cutoffTimestamp",
    )
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    /**
     * Counts cooldown records.
     *
     * @return The number of rows in the table.
     */
    @Query("SELECT COUNT(*) FROM ${AutoReplyCooldownEntity.TABLE_NAME}")
    suspend fun count(): Int
}
