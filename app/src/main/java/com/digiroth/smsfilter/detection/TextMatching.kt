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

import java.text.Normalizer

/**
 * Pure text-matching primitives shared by the stop list and opt-out detection.
 *
 * Kept in one place so that "whole word" means exactly the same thing to a stop-list keyword as it
 * does to an opt-out pattern evaluated with `MatchMode.LAST_LINE_CONTAINS`. Free of Android
 * dependencies, so it is unit-testable on the JVM.
 */
object TextMatching {

    /**
     * Folds "styled" Unicode text into its plain equivalent using NFKC compatibility normalization.
     *
     * Fundraising and marketing texts frequently write names in decorative characters such as
     * mathematical bold (𝗖𝗼𝗼𝗸𝗲), italic (𝘱𝘭𝘦𝘢𝘴𝘦), or full-width (Ｃｏｏｋｅ) letters. Those are
     * different code points from ordinary letters, so a plain keyword like `cooke` never matches
     * them. NFKC maps each of them back to the ordinary letter, preserving case, and also turns
     * the non-breaking space into a regular space.
     *
     * The result may differ in length from the input, so it must only be used for matching, never
     * to compute offsets into the original text.
     *
     * @param text The text to normalize.
     * @return The NFKC-normalized text.
     */
    fun normalizeStyledText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /**
     * Whether [needle] occurs in [haystack], either anywhere or only as a whole word.
     *
     * @param haystack The text to search.
     * @param needle The text to look for. An empty needle never matches.
     * @param ignoreCase `true` to compare case-insensitively.
     * @param wholeWord `true` to require a word boundary on both sides of the occurrence; `false`
     *   for a plain substring test.
     * @return `true` if a qualifying occurrence exists.
     */
    fun contains(haystack: String, needle: String, ignoreCase: Boolean, wholeWord: Boolean): Boolean {
        if (needle.isEmpty()) return false
        return if (wholeWord) {
            containsWholeWord(haystack, needle, ignoreCase)
        } else {
            haystack.contains(needle, ignoreCase = ignoreCase)
        }
    }

    /**
     * Whether [needle] occurs in [haystack] as a whole word.
     *
     * "Whole word" means the occurrence has a non-alphanumeric character, or nothing at all, on
     * both sides. So `avis` matches "Avis," and "(Avis)" but not "Davis" or "Avisa", and `end`
     * matches "Reply END." but not "weekend". Digits count as word characters, so `stop` does not
     * match inside "stop2stop".
     *
     * Every occurrence is examined, not just the first: "Davis and Avis" contains `avis` twice,
     * and only the second is a whole word.
     *
     * The boundary is judged on the characters immediately outside the needle, whatever the
     * needle's own first and last characters are. A needle such as `PG&E` therefore needs a
     * boundary before the `P` and after the `E`.
     *
     * @param haystack The text to search.
     * @param needle The text to look for. An empty needle never matches.
     * @param ignoreCase `true` to compare case-insensitively.
     * @return `true` if at least one whole-word occurrence exists.
     */
    fun containsWholeWord(haystack: String, needle: String, ignoreCase: Boolean): Boolean {
        if (needle.isEmpty()) return false

        var index = haystack.indexOf(needle, startIndex = 0, ignoreCase = ignoreCase)
        while (index >= 0) {
            val before = index - 1
            val after = index + needle.length
            val boundedLeft = (before < 0) || !haystack[before].isWordChar()
            val boundedRight = (after >= haystack.length) || !haystack[after].isWordChar()
            if (boundedLeft && boundedRight) return true
            index = haystack.indexOf(needle, startIndex = index + 1, ignoreCase = ignoreCase)
        }
        return false
    }

    /**
     * Whether a character is part of a word for boundary purposes.
     *
     * Letters and digits are word characters; everything else — spaces, punctuation, emoji, the
     * start and end of the text — is a boundary.
     *
     * @return `true` if this character is a letter or digit.
     */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit()
}
