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

import androidx.room.TypeConverter
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.MessageSource
import com.digiroth.smsfilter.data.db.entity.ReplyType

/**
 * Room type converters for the enums stored in this database.
 *
 * Persists [ReplyType], [MatchMode], [LogEventType], and [MessageSource].
 *
 * Every enum is persisted as its `name` string rather than its ordinal, so that reordering
 * or inserting enum constants can never silently reinterpret existing rows. The seeding SQL
 * in `AppDatabase.SEED_CALLBACK` writes these same string values directly, so the two must
 * stay in agreement.
 *
 * Conversion of an unrecognized string throws rather than substituting a default: a value
 * the app does not understand indicates real data corruption, and failing loudly is safer
 * than silently treating an unknown match mode as `ANYWHERE`.
 *
 * ## Safe and unsafe ways to change these enums
 *
 * Because the stored value is the constant's `name`, the enum declaration and the rows already
 * on disk are two halves of one contract. `valueOf` throws [IllegalArgumentException] on any
 * string it does not recognize, and that exception surfaces wherever the row is read — for
 * [LogEventType] that is inside the log screen's Flow, which crashes the screen rather than
 * showing an empty list.
 *
 * **Safe: adding a new constant.** Existing rows keep names the code still knows, and the new
 * name only ever appears in rows written by the build that understands it. No migration and no
 * version bump is required, because the column type does not change. `LogEventType.NO_MATCH`
 * was added exactly this way.
 *
 * **Safe: reordering constants.** Nothing depends on ordinal position; that is the whole reason
 * `name` is stored instead of `ordinal`.
 *
 * **Unsafe — do not do this: renaming or deleting a constant without bumping
 * `AppDatabase`'s `version`.** Every row already holding the old name becomes unreadable, and
 * the next read throws. Room only rebuilds the tables when the version changes, so leaving the
 * version alone guarantees the stale rows survive to crash the app. If a constant must be
 * renamed or removed, bump the version in the same change; the destructive-migration fallback
 * configured in `DatabaseModule.provideAppDatabase` then drops the tables and the stale strings
 * go with them. Note that this discards all user data — see that function's documentation.
 *
 * **Unsafe — do not do this: renaming a constant while leaving `AppDatabase.SEED_CALLBACK`
 * untouched.** The seeding SQL writes these strings as literals, so the two drift apart
 * silently and a fresh install seeds rows the converters cannot read back.
 *
 * **Do not "fix" a crash here by falling back to a default.** Compare
 * `ConnectionStatus.fromStoredValue`, which deliberately does tolerate unknown input: it backs a
 * status indicator that the next live check overwrites, so guessing costs nothing. These enums
 * are different in kind. A misread [MatchMode] silently changes which messages are detected, and
 * a misread [ReplyType] sends the wrong keyword to a real person. Throwing is the correct
 * behaviour for those two, and [LogEventType] follows the same rule for consistency.
 */
class RoomConverters {

    /**
     * @param value The [ReplyType] to persist, or `null`.
     * @return The enum's `name`, or `null`.
     */
    @TypeConverter
    fun fromReplyType(value: ReplyType?): String? = value?.name

    /**
     * @param value A persisted [ReplyType] name, or `null`.
     * @return The matching [ReplyType], or `null` if the input was `null`.
     * @throws IllegalArgumentException if the stored string is not a known [ReplyType].
     */
    @TypeConverter
    fun toReplyType(value: String?): ReplyType? = value?.let(ReplyType::valueOf)

    /**
     * @param value The [MatchMode] to persist, or `null`.
     * @return The enum's `name`, or `null`.
     */
    @TypeConverter
    fun fromMatchMode(value: MatchMode?): String? = value?.name

    /**
     * @param value A persisted [MatchMode] name, or `null`.
     * @return The matching [MatchMode], or `null` if the input was `null`.
     * @throws IllegalArgumentException if the stored string is not a known [MatchMode].
     */
    @TypeConverter
    fun toMatchMode(value: String?): MatchMode? = value?.let(MatchMode::valueOf)

    /**
     * @param value The [LogEventType] to persist, or `null`.
     * @return The enum's `name`, or `null`.
     */
    @TypeConverter
    fun fromLogEventType(value: LogEventType?): String? = value?.name

    /**
     * @param value A persisted [LogEventType] name, or `null`.
     * @return The matching [LogEventType], or `null` if the input was `null`.
     * @throws IllegalArgumentException if the stored string is not a known [LogEventType].
     */
    @TypeConverter
    fun toLogEventType(value: String?): LogEventType? = value?.let(LogEventType::valueOf)

    /**
     * @param value The [MessageSource] to persist, or `null`.
     * @return The enum's `name`, or `null`.
     */
    @TypeConverter
    fun fromMessageSource(value: MessageSource?): String? = value?.name

    /**
     * @param value A persisted [MessageSource] name, or `null`.
     * @return The matching [MessageSource], or [MessageSource.SMS] if the input was `null`.
     * @throws IllegalArgumentException if the stored string is not a known [MessageSource].
     */
    @TypeConverter
    fun toMessageSource(value: String?): MessageSource? = value?.let(MessageSource::valueOf) ?: MessageSource.SMS
}
