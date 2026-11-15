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
 * A user-defined stop-list keyword (Tier 1 of opt-out detection).
 *
 * If a stop-list keyword matches an incoming message body, the message is ignored entirely and
 * never reaches opt-out detection. Each keyword carries its own matching options; with both off
 * (the default) it matches case-insensitively as a substring anywhere in the body. The list starts
 * empty; the user populates it from the Rules screen.
 *
 * The two option columns were added in schema version 6 (`AppDatabase.MIGRATION_5_6`) with a
 * default of `0`, so every keyword created before then keeps its original substring,
 * case-insensitive behaviour. The `defaultValue` on each column must stay identical to the
 * migration's `DEFAULT 0`, or Room's schema validation fails on upgraded installs.
 *
 * @property id Auto-generated row identifier.
 * @property keyword The keyword to match, stored as entered. Unique across the table with an exact
 *   (case-sensitive) comparison, so `Avis` and `avis` may exist as separate entries.
 * @property caseSensitive `true` to require the message to use the keyword's exact capitalization;
 *   `false` to ignore case.
 * @property wholeWord `true` to match only where the keyword stands as a whole word (bounded by
 *   spaces, punctuation, or the start/end of the message); `false` to match it anywhere, including
 *   inside a longer word.
 */
@Entity(
    tableName = StopListEntity.TABLE_NAME,
    indices = [Index(value = ["keyword"], unique = true)],
)
data class StopListEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "keyword")
    val keyword: String,

    @ColumnInfo(name = "case_sensitive", defaultValue = "0")
    val caseSensitive: Boolean = false,

    @ColumnInfo(name = "whole_word", defaultValue = "0")
    val wholeWord: Boolean = false,
) {
    companion object {
        /** Room table name for stop-list keywords. */
        const val TABLE_NAME: String = "stop_list"
    }
}
