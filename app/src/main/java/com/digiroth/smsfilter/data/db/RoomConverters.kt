/*
 * Copyright (c) 2025 Bill Roth <bill.roth@gmail.com>
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
import com.digiroth.smsfilter.data.db.entity.ReplyType

/**
 * Room type converters for the enums stored in this database.
 *
 * Every enum is persisted as its `name` string rather than its ordinal, so that reordering
 * or inserting enum constants can never silently reinterpret existing rows. The seeding SQL
 * in `AppDatabase.SEED_CALLBACK` writes these same string values directly, so the two must
 * stay in agreement.
 *
 * Conversion of an unrecognized string throws rather than substituting a default: a value
 * the app does not understand indicates real data corruption, and failing loudly is safer
 * than silently treating an unknown match mode as `ANYWHERE`.
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
}
