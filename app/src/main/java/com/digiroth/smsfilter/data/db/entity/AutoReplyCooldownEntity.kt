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

package com.digiroth.smsfilter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Record of the most recent auto-reply sent to one sender, used to enforce the fixed
 * 24-hour reply cooldown.
 *
 * The cooldown exists to prevent SMS ping-pong loops: an automated responder may answer
 * our "stop" with a confirmation text that itself trips an opt-out pattern, which without
 * this gate would trigger replies back and forth indefinitely — exhausting the user's SMS
 * allowance and risking a carrier spam flag.
 *
 * **Privacy:** [senderHash] is a one-way SHA-256 hash, never the sender address itself.
 * This preserves the project-wide rule that no phone number data is stored locally; the
 * hash cannot be reversed into a number, but is stable enough to recognise a repeat sender.
 *
 * @property senderHash Lowercase-hex SHA-256 hash of the **raw** originating address, used
 *   as the primary key. Hashing the raw address (not the E.164-normalized form) keeps short
 *   codes and standard numbers consistent with how replies are addressed.
 * @property lastReplyTimestamp When the last auto-reply was sent, in epoch milliseconds.
 */
@Entity(tableName = AutoReplyCooldownEntity.TABLE_NAME)
data class AutoReplyCooldownEntity(
    @PrimaryKey
    @ColumnInfo(name = "sender_hash")
    val senderHash: String,

    @ColumnInfo(name = "last_reply_timestamp")
    val lastReplyTimestamp: Long,
) {
    companion object {
        /** Room table name for cooldown records. */
        const val TABLE_NAME: String = "auto_reply_cooldown"

        /**
         * The cooldown window in milliseconds (24 hours). Fixed by the specification and
         * deliberately not user-configurable.
         */
        const val COOLDOWN_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    }
}
