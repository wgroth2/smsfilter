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
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The one-word keyword sent back to a sender when an opt-out signal is detected.
 *
 * The [keyword] is the literal SMS body of the auto-reply, so it must stay lowercase and
 * single-word — carriers and marketing platforms match these exactly.
 *
 * @property keyword The exact text sent as the auto-reply body.
 */
enum class ReplyType(val keyword: String) {
    /** Reply with "stop". */
    STOP("stop"),

    /** Reply with "end". */
    END("end"),
    ;

    companion object {
        /**
         * Resolves a [ReplyType] from its stored keyword, case-insensitively.
         *
         * @param keyword The reply keyword, e.g. `"stop"`.
         * @return The matching [ReplyType], or `null` if the keyword is unrecognized.
         */
        fun fromKeyword(keyword: String): ReplyType? =
            entries.firstOrNull { it.keyword.equals(keyword, ignoreCase = true) }
    }
}

/**
 * How an opt-out pattern is evaluated against a message body.
 *
 * All matching is case-insensitive regardless of mode. The detector must read this value
 * from each stored pattern and must never special-case particular pattern strings, so that
 * user-added patterns behave identically to the seeded defaults.
 */
enum class MatchMode {
    /** The pattern matches as a substring anywhere in the message body. */
    ANYWHERE,

    /**
     * The pattern matches only if the last non-empty line of the message, after trimming
     * whitespace, is exactly the pattern word. This is what prevents "reply STOP to
     * unsubscribe" from being treated as an opt-out request.
     */
    LAST_LINE_EXACT,
}

/**
 * A configurable opt-out pattern (Tier 2 of opt-out detection).
 *
 * Patterns are fully user-editable. The table is seeded on first database creation with
 * four defaults (see `AppDatabase.SEED_CALLBACK`), but nothing in the detection engine
 * depends on those specific rows existing.
 *
 * @property id Auto-generated row identifier.
 * @property pattern The pattern text to match against the message body.
 * @property replyType Which one-word keyword to send if this pattern matches.
 * @property matchMode How this pattern is evaluated.
 */
@Entity(
    tableName = OptOutPatternEntity.TABLE_NAME,
    indices = [Index(value = ["pattern", "match_mode"], unique = true)],
)
data class OptOutPatternEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "pattern")
    val pattern: String,

    @ColumnInfo(name = "reply_type")
    val replyType: ReplyType,

    @ColumnInfo(name = "match_mode")
    val matchMode: MatchMode,
) {
    companion object {
        /** Room table name for opt-out patterns. */
        const val TABLE_NAME: String = "opt_out_patterns"
    }
}
