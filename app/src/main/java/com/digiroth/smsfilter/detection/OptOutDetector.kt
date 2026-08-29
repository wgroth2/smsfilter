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

package com.digiroth.smsfilter.detection

import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import javax.inject.Inject

/**
 * Tier 2 of opt-out detection: decides whether a message is asking to be unsubscribed.
 *
 * Runs only after the stop list has produced no match. Each pattern is evaluated according to
 * its own [MatchMode], read from the entity — this class never special-cases particular pattern
 * strings, so a user-added pattern behaves exactly like a seeded default.
 *
 * The pattern list is supplied by the caller rather than injected as a DAO. `SmsLookupWorker`
 * fetches the live list and passes it in, which keeps this class pure and unit-testable on the
 * JVM with no database and no mocking.
 */
class OptOutDetector @Inject constructor() {

    /**
     * Evaluates a message body against the configured opt-out patterns.
     *
     * All matching is case-insensitive. When several patterns match, the first in the supplied
     * list order wins, so the caller's ordering defines precedence deterministically.
     *
     * Blank patterns are skipped: an empty string is a substring of every message and the last
     * line of none, so a blank row would otherwise match everything under
     * [MatchMode.ANYWHERE].
     *
     * @param body The fully reconstructed message body. Multi-part messages must already be
     *   concatenated — evaluating a single segment would break [MatchMode.LAST_LINE_EXACT],
     *   whose whole purpose depends on seeing the true final line.
     * @param patterns The current opt-out patterns; may be empty.
     * @return The first matching pattern as an [OptOutResult], or `null` if none match.
     */
    fun detect(body: String, patterns: List<OptOutPatternEntity>): OptOutResult? {
        if (body.isEmpty() || patterns.isEmpty()) return null

        // Computed once rather than per pattern; most messages have several patterns to test.
        val lastLine: String? = lastNonBlankLine(body)

        val match = patterns.firstOrNull { pattern ->
            pattern.pattern.isNotBlank() && when (pattern.matchMode) {
                MatchMode.ANYWHERE -> body.contains(pattern.pattern, ignoreCase = true)
                MatchMode.LAST_LINE_EXACT -> lastLine != null &&
                    lastLine.equals(pattern.pattern.trim { it.isBlankChar() }, ignoreCase = true)
                MatchMode.LAST_LINE_CONTAINS -> lastLine != null &&
                    containsAsWord(lastLine, pattern.pattern.trim { it.isBlankChar() })
            }
        } ?: return null

        return OptOutResult(
            pattern = match.pattern,
            replyType = match.replyType,
            matchMode = match.matchMode,
        )
    }

    /**
     * Whether [needle] occurs in [haystack] as a whole word, case-insensitively.
     *
     * "Whole word" means the occurrence has a non-alphanumeric character, or nothing at all, on
     * both sides. This is what separates [MatchMode.LAST_LINE_CONTAINS] from a plain substring
     * test, and the separation is load-bearing: `end` as a substring matches "weekend",
     * "recommend" and "send", so a substring test would auto-reply to a cheerful sign-off.
     *
     * Every occurrence is examined, not just the first. A last line of "stopandshop.com — reply
     * STOP" contains `stop` twice, and only the second is a word; returning on the first would
     * miss a genuine opt-out.
     *
     * Digits count as word characters, so `stop` does not match inside "stop2stop". That pattern
     * is seeded separately under [MatchMode.ANYWHERE] and is meant to be matched as its own token.
     *
     * @param haystack The text to search, already trimmed.
     * @param needle The pattern to look for, already trimmed. An empty needle never matches.
     * @return `true` if at least one whole-word occurrence exists.
     */
    private fun containsAsWord(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false

        var index = haystack.indexOf(needle, startIndex = 0, ignoreCase = true)
        while (index >= 0) {
            val before = index - 1
            val after = index + needle.length
            val boundedLeft = before < 0 || !haystack[before].isWordChar()
            val boundedRight = after >= haystack.length || !haystack[after].isWordChar()
            if (boundedLeft && boundedRight) return true
            index = haystack.indexOf(needle, startIndex = index + 1, ignoreCase = true)
        }
        return false
    }

    /**
     * Extracts the last line of the body that contains something other than whitespace, trimmed.
     *
     * Trailing blank lines are common in marketing SMS (signatures, padding), and treating one
     * of them as "the last line" would make [MatchMode.LAST_LINE_EXACT] fail on every such
     * message.
     *
     * @param body The message body.
     * @return The trimmed last non-blank line, or `null` if the body has no non-blank line.
     */
    private fun lastNonBlankLine(body: String): String? = body
        .lines()
        .lastOrNull { line -> !line.all { it.isBlankChar() } }
        ?.trim { it.isBlankChar() }

    private companion object {
        /**
         * Whether a character should count as surrounding whitespace.
         *
         * Deliberately broader than [Char.isWhitespace] alone. `Character.isWhitespace` returns
         * `false` for the non-breaking space (U+00A0) and other Unicode space separators, which
         * appear in real marketing SMS — so a message ending in `"STOP "` would otherwise
         * fail to match the `stop` last-line pattern and the opt-out would be silently missed.
         * Combining both predicates covers the space-separator category as well.
         *
         * @return `true` if this character is standard whitespace or a Unicode space character.
         */
        fun Char.isBlankChar(): Boolean = isWhitespace() || Character.isSpaceChar(this)

        /**
         * Whether a character is part of a word for [MatchMode.LAST_LINE_CONTAINS] boundary
         * purposes.
         *
         * Letters and digits are word characters; everything else — spaces, punctuation, the
         * start and end of the line — is a boundary. Punctuation counting as a boundary is what
         * lets "STOP to cancel." and "...help, STOP" both match.
         *
         * @return `true` if this character is a letter or digit.
         */
        fun Char.isWordChar(): Boolean = isLetterOrDigit()
    }
}
