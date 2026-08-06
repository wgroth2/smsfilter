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
 * What a log row records: an opt-out detection, a message that was ignored, or a message that was
 * examined in full and simply did not match anything.
 *
 * Drives the "All" / "Detections Only" / "Ignored Only" / "Not Matched" filter chips on the log
 * screen. Persisted by name through `RoomConverters`, so adding a constant here changes no column
 * type and needs no schema migration — but removing or renaming one would orphan existing rows.
 */
enum class LogEventType {
    /** An opt-out signal was detected; [DetectionLogEntity.replyStatus] records the outcome. */
    DETECTION,

    /** The message was ignored; [DetectionLogEntity.ignoreReason] records why. */
    IGNORED,

    /**
     * The message came from an unknown sender, passed the stop list, and contained no opt-out
     * pattern, so no action was taken.
     *
     * Recorded purely for observability. Without a row for this path a correctly-processed message
     * leaves no evidence at all, making a working app indistinguishable from one that never
     * received the broadcast. Only [DetectionLogEntity.timestamp] and
     * [DetectionLogEntity.messagePreview] are populated.
     */
    NO_MATCH,
}

/**
 * One entry in the activity and detection log.
 *
 * A single table backs both detections and ignored events, discriminated by [eventType],
 * so the log screen can render a single chronological list and filter it client-side.
 *
 * **Privacy:** this row must never contain a phone number. [messagePreview] is a truncated
 * excerpt of the message body only, and no sender address — hashed or otherwise — is
 * recorded here.
 *
 * @property id Auto-generated row identifier.
 * @property timestamp When the event occurred, in epoch milliseconds.
 * @property eventType Whether this is a detection, an ignored message, or a message that matched
 *   nothing.
 * @property matchedPattern For detections, the opt-out pattern that matched; `null` for
 *   ignored and unmatched events.
 * @property replyStatus For detections, the human-readable auto-reply outcome — for
 *   example `"Reply sent: stop"`, `"Reply skipped: dry run"`, `"Reply skipped: cooldown"`,
 *   or `"Reply skipped: alphanumeric sender"`. `null` for ignored and unmatched events.
 * @property ignoreReason For ignored events, why the message was ignored — for example
 *   `"Ignored: Known Google Contact"`. `null` for detections and unmatched events.
 * @property messagePreview A truncated excerpt of the message body, never containing a
 *   phone number.
 */
@Entity(
    tableName = DetectionLogEntity.TABLE_NAME,
    indices = [Index(value = ["timestamp"]), Index(value = ["event_type"])],
)
data class DetectionLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "event_type")
    val eventType: LogEventType,

    @ColumnInfo(name = "matched_pattern")
    val matchedPattern: String? = null,

    @ColumnInfo(name = "reply_status")
    val replyStatus: String? = null,

    @ColumnInfo(name = "ignore_reason")
    val ignoreReason: String? = null,

    @ColumnInfo(name = "message_preview")
    val messagePreview: String,
) {
    companion object {
        /** Room table name for log entries. */
        const val TABLE_NAME: String = "detection_log"

        /**
         * Maximum number of entries the log screen displays, per the specification.
         * Applied as a query `LIMIT` rather than by pruning rows.
         */
        const val MAX_DISPLAYED_ENTRIES: Int = 100

        /** Maximum characters retained in [messagePreview] before truncation. */
        const val PREVIEW_MAX_LENGTH: Int = 160
    }
}
