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

package com.digiroth.smsfilter.receiver

import javax.inject.Inject

/**
 * One message text carried by an incoming messaging notification.
 *
 * @property text The message text, already trimmed and known to be non-blank.
 * @property isFromSelf Whether the user wrote this message rather than receiving it. Outgoing
 *   messages appear in a `MessagingStyle` notification alongside incoming ones, and evaluating
 *   the user's own words for opt-out patterns would auto-reply to a conversation nobody asked to
 *   leave.
 */
data class NotificationFragment(
    val text: String,
    val isFromSelf: Boolean,
)

/**
 * Decides which text in a messaging notification is the message that just arrived.
 *
 * Pure by design — no `android.*` types — so this decision can be unit-tested on the JVM, in the
 * same spirit as `PermissionStateEvaluator` and `ConnectionHealthEvaluator`.
 * `RcsNotificationListenerService` does the platform-side extraction and delegates the choice here.
 *
 * ## Why this is not "join everything and take the longest"
 *
 * A `MessagingStyle` notification carries recent conversation history, not just the message that
 * triggered it. Concatenating every fragment and preferring the longest candidate — the previous
 * behaviour — produced a body that was the whole visible thread, attributed wholesale to whoever
 * sent the newest message. Three things went wrong with that:
 *
 *  - a stop-list keyword in an older message suppressed a new one that never contained it;
 *  - an [com.digiroth.smsfilter.data.db.entity.MatchMode.ANYWHERE] pattern in an older message
 *    re-triggered detection on every later notification in the thread;
 *  - text the user had written was evaluated as though the other party had sent it.
 *
 * Only the newest incoming fragment is the new message, so that is what this class returns.
 *
 * ## The truncation trade-off
 *
 * Dropping the join gives up the one thing it was good for: a long message whose `MessagingStyle`
 * fragment is elided still has its full text in `EXTRA_BIG_TEXT`. That case is recovered
 * explicitly rather than accidentally — a truncated fragment is replaced only by a candidate that
 * demonstrably continues it, never by an arbitrary longer string.
 */
class NotificationBodyAssembler @Inject constructor() {

    /**
     * Selects the body to evaluate from one notification's text sources.
     *
     * @param fragments Message fragments in the notification's own order, oldest first, as
     *   `MessagingStyle` reports them.
     * @param bigText The `EXTRA_BIG_TEXT` value, or `null`.
     * @param text The `EXTRA_TEXT` value, or `null`.
     * @param textLines The joined `EXTRA_TEXT_LINES` values, or `null`.
     * @return The message body to evaluate, or `null` when the notification carries no usable text.
     */
    fun assemble(
        fragments: List<NotificationFragment>,
        bigText: String?,
        text: String?,
        textLines: String?,
    ): String? {
        val extras: List<String> = listOfNotNull(bigText, text, textLines)
            .map(String::trim)
            .filter(String::isNotBlank)

        val newest: String? = fragments
            .filterNot(NotificationFragment::isFromSelf)
            .map { it.text.trim() }
            .lastOrNull(String::isNotBlank)

        if (newest == null) {
            // No incoming fragment to anchor on — either the notification carries no MessagingStyle
            // at all, or every fragment was the user's own. Fall back to the single-message extras,
            // longest first; none of them is a concatenation of the thread.
            return extras.maxByOrNull(String::length)
        }

        if (!isTruncated(newest)) return newest

        // The fragment was elided. Accept a longer extra only when it demonstrably continues the
        // same message, so an unrelated summary line can never displace the real one.
        val stem = truncationStem(newest)
        return extras
            .filter { candidate ->
                (candidate.length > newest.length) && candidate.startsWith(stem, ignoreCase = true)
            }
            .maxByOrNull(String::length)
            ?: newest
    }

    /**
     * Whether a notification text was elided by the posting app.
     *
     * @param value The text to inspect.
     * @return `true` if it ends in either ellipsis form.
     */
    private fun isTruncated(value: String): Boolean =
        value.endsWith(UNICODE_ELLIPSIS) || value.endsWith(ASCII_ELLIPSIS)

    /**
     * Strips the trailing ellipsis so the remainder can be matched against a fuller copy.
     *
     * @param value The truncated text.
     * @return The text without its ellipsis marker or trailing whitespace.
     */
    private fun truncationStem(value: String): String = value
        .removeSuffix(UNICODE_ELLIPSIS)
        .removeSuffix(ASCII_ELLIPSIS)
        .trim()

    private companion object {
        /** Single-character ellipsis, the form Google Messages uses. */
        const val UNICODE_ELLIPSIS: String = "…"

        /** Three-period ellipsis, used by some OEM messaging apps. */
        const val ASCII_ELLIPSIS: String = "..."
    }
}
