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

import com.digiroth.smsfilter.data.db.entity.StopListEntity
import javax.inject.Inject

/**
 * Tier 1 of opt-out detection: decides whether a message should be ignored outright.
 *
 * If any user-defined stop-list keyword matches the message body, the message is ignored and
 * never reaches opt-out detection. This runs before any contact lookup so a stop-listed message
 * costs no network calls.
 *
 * The keyword list is passed in by the caller rather than injected as a DAO, which keeps this
 * class pure and unit-testable on the JVM with no database.
 */
class StopListMatcher @Inject constructor() {

    /**
     * Finds the first stop-list keyword that matches the message body.
     *
     * Each keyword is matched according to its own options:
     * - [StopListEntity.wholeWord] `false` (the default): a substring test, so `"promo"` also
     *   matches `"promotional"` and `"avis"` matches `"Davis"`. `true`: the keyword must stand as a
     *   whole word, so `"avis"` matches `"Avis,"` but not `"Davis"`.
     * - [StopListEntity.caseSensitive] `false` (the default): capitalization is ignored. `true`:
     *   the message must use the keyword's exact capitalization.
     *
     * Both the body and the keyword are first passed through
     * [TextMatching.normalizeStyledText], so a keyword typed in plain letters still matches a name
     * written in decorative Unicode such as mathematical bold ("𝗖𝗼𝗼𝗸𝗲").
     *
     * Blank keywords are skipped. An empty string is a substring of every string, so a blank
     * row — reachable if the UI ever admits one — would otherwise silence the entire app.
     *
     * @param body The fully reconstructed message body.
     * @param keywords The current stop list; may be empty.
     * @return The first matching keyword entry in list order, or `null` if none match.
     */
    fun findMatch(body: String, keywords: List<StopListEntity>): StopListEntity? {
        if (body.isEmpty() || keywords.isEmpty()) return null
        // Normalized once rather than per keyword; most messages are tested against every entry.
        val normalizedBody = TextMatching.normalizeStyledText(body)
        return keywords.firstOrNull { entry ->
            entry.keyword.isNotBlank() &&
                TextMatching.contains(
                    haystack = normalizedBody,
                    needle = TextMatching.normalizeStyledText(entry.keyword),
                    ignoreCase = !entry.caseSensitive,
                    wholeWord = entry.wholeWord,
                )
        }
    }

    /**
     * Whether the message body contains any stop-list keyword.
     *
     * @param body The fully reconstructed message body.
     * @param keywords The current stop list; may be empty.
     * @return `true` if the message should be ignored.
     */
    fun matches(body: String, keywords: List<StopListEntity>): Boolean =
        findMatch(body, keywords) != null
}
