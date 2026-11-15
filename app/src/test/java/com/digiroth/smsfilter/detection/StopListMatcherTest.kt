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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [StopListMatcher].
 *
 * Verifies stop-list keyword matching semantics, including case-insensitivity,
 * substring inclusion, multi-word matching, blank keyword handling, and first-match selection,
 * plus the per-keyword whole-word and case-sensitive options and styled-Unicode normalization.
 */
class StopListMatcherTest {

    private val matcher = StopListMatcher()

    private fun stopList(vararg keywords: String): List<StopListEntity> =
        keywords.mapIndexed { index, keyword ->
            StopListEntity(id = index + 1L, keyword = keyword)
        }

    /**
     * Builds a one-entry stop list with explicit options.
     *
     * @param keyword The keyword text.
     * @param caseSensitive Whether the entry is case sensitive.
     * @param wholeWord Whether the entry matches whole words only.
     * @return A single-element list.
     */
    private fun entry(keyword: String, caseSensitive: Boolean = false, wholeWord: Boolean = false): List<StopListEntity> =
        listOf(StopListEntity(id = 1L, keyword = keyword, caseSensitive = caseSensitive, wholeWord = wholeWord))

    /**
     * Tests the reported problem: a default (substring) keyword "Avis" still matches "Davis",
     * preserving the behaviour of every keyword created before the options existed.
     *
     * Preconditions: Keyword "Avis" with both options off.
     * Expected: Matches "Davis".
     */
    @Test
    fun `default options keep substring matching`() {
        assertTrue(matcher.matches("Paid for by Davis for Congress", entry("Avis")))
    }

    /**
     * Tests that whole-word matching stops "Avis" from matching "Davis" but still catches Avis.
     *
     * Preconditions: Keyword "Avis" with whole word on, case insensitive.
     * Expected: No match on "Davis"; match on "AVIS," and "avis".
     */
    @Test
    fun `whole word keyword does not match inside a longer word`() {
        val keywords = entry("Avis", wholeWord = true)

        assertFalse(matcher.matches("Paid for by Davis for Congress", keywords))
        assertTrue(matcher.matches("Your AVIS, rental is ready", keywords))
        assertTrue(matcher.matches("rent from avis today", keywords))
    }

    /**
     * Tests that a case-sensitive keyword requires its exact capitalization.
     *
     * Preconditions: Keyword "Avis" with case sensitive on.
     * Expected: No match on "AVIS" or on "Davis" (whose "avis" is lowercase); match on "Avis".
     */
    @Test
    fun `case sensitive keyword requires exact capitalization`() {
        val keywords = entry("Avis", caseSensitive = true)

        assertFalse(matcher.matches("RENT FROM AVIS", keywords))
        assertFalse(matcher.matches("Call Davis", keywords))
        assertTrue(matcher.matches("Rent from Avis", keywords))
    }

    /**
     * Tests that a plain keyword matches a name written in decorative bold Unicode letters.
     *
     * Preconditions: Keyword "cooke"; message containing "𝗖𝗼𝗼𝗸𝗲" (mathematical sans-serif bold).
     * Expected: Match in substring mode and in whole-word mode.
     */
    @Test
    fun `plain keyword matches styled unicode text`() {
        val body = "Help \uD835\uDDD6\uD835\uDDFC\uD835\uDDFC\uD835\uDDF8\uD835\uDDF2 win in WI-03"

        assertTrue(matcher.matches(body, entry("cooke")))
        assertTrue(matcher.matches(body, entry("cooke", wholeWord = true)))
    }

    /**
     * Tests that styled Unicode keeps its capitalization through normalization, so a
     * case-sensitive keyword is still judged on case.
     *
     * Preconditions: Case-sensitive keyword "cooke"; message with bold capital-C "Cooke".
     * Expected: No match.
     */
    @Test
    fun `styled unicode keeps its capitalization`() {
        val body = "Help \uD835\uDDD6\uD835\uDDFC\uD835\uDDFC\uD835\uDDF8\uD835\uDDF2 win"

        assertFalse(matcher.matches(body, entry("cooke", caseSensitive = true)))
    }

    /**
     * Tests that options are evaluated per keyword: a whole-word entry that does not match does not
     * stop a later substring entry from matching.
     *
     * Preconditions: "avis" (whole word) followed by "vis" (substring), message "Davis".
     * Expected: The second entry is returned.
     */
    @Test
    fun `options are evaluated per keyword`() {
        val keywords = listOf(
            StopListEntity(id = 1L, keyword = "avis", wholeWord = true),
            StopListEntity(id = 2L, keyword = "vis"),
        )

        assertEquals(2L, matcher.findMatch("Call Davis", keywords)?.id)
    }

    /**
     * Tests that a message containing a configured stop-list keyword returns true for matching.
     *
     * Preconditions: Keyword list contains "promo" and message contains "Big promo inside!".
     * Expected: [StopListMatcher.matches] returns true.
     */
    @Test
    fun `matches keyword appearing in the message`() {
        val keywords = stopList("promo")

        assertTrue(matcher.matches("Big promo inside!", keywords))
    }

    /**
     * Tests that matching is case-insensitive in both stored lowercase vs uppercase message and vice versa.
     *
     * Preconditions: Messages with differing casing than the stored keywords.
     * Expected: [StopListMatcher.matches] returns true for both combinations.
     */
    @Test
    fun `matching is case insensitive in both directions`() {
        assertTrue(
            "stored lowercase should match uppercase text",
            matcher.matches("BIG PROMO INSIDE", stopList("promo")),
        )
        assertTrue(
            "stored uppercase should match lowercase text",
            matcher.matches("big promo inside", stopList("PROMO")),
        )
    }

    /**
     * Tests that keywords match as substrings inside longer words.
     *
     * Preconditions: Keyword "promo" against message containing "promotional".
     * Expected: [StopListMatcher.matches] returns true.
     */
    @Test
    fun `keywords match as substrings inside longer words`() {
        // Documented behaviour: the stop list is a coarse filter, and over-matching only means a
        // message is left alone, which is the safe direction.
        assertTrue(matcher.matches("This is promotional content", stopList("promo")))
    }

    /**
     * Tests that a message containing none of the stop-list keywords returns false.
     *
     * Preconditions: Keyword list with "promo" and "sale" tested against an appointment confirmation message.
     * Expected: [StopListMatcher.matches] returns false.
     */
    @Test
    fun `does not match when no keyword is present`() {
        assertFalse(matcher.matches("Your appointment is confirmed", stopList("promo", "sale")))
    }

    /**
     * Tests that an empty keyword list never matches any message.
     *
     * Preconditions: Empty keyword list.
     * Expected: [StopListMatcher.matches] returns false.
     */
    @Test
    fun `empty keyword list never matches`() {
        assertFalse(matcher.matches("Big promo inside!", emptyList()))
    }

    /**
     * Tests that an empty message string never matches any keyword list.
     *
     * Preconditions: Empty string message against keyword "promo".
     * Expected: [StopListMatcher.matches] returns false.
     */
    @Test
    fun `empty message never matches`() {
        assertFalse(matcher.matches("", stopList("promo")))
    }

    /**
     * Tests that empty or blank keywords in the stop list are ignored and do not cause universal matching.
     *
     * Preconditions: Keyword list with empty string or whitespace-only strings.
     * Expected: [StopListMatcher.matches] returns false for any message.
     */
    @Test
    fun `blank keyword is ignored rather than matching everything`() {
        // An empty string is a substring of every string, so a blank row would otherwise silence
        // the entire app.
        assertFalse(matcher.matches("Any message at all", stopList("")))
        assertFalse(matcher.matches("Any message at all", stopList("   ")))
    }

    /**
     * Tests that a leading blank keyword in the list does not prevent subsequent valid keywords from matching.
     *
     * Preconditions: Keyword list contains "" followed by "promo".
     * Expected: [StopListMatcher.findMatch] successfully matches "promo".
     */
    @Test
    fun `blank keyword does not mask a real keyword later in the list`() {
        val keywords = stopList("", "promo")

        val match = matcher.findMatch("Big promo inside!", keywords)

        assertEquals("promo", match?.keyword)
    }

    /**
     * Tests that findMatch returns the matched entity so its keyword and ID can be logged.
     *
     * Preconditions: Keyword list containing "sale" (id=1) and "promo" (id=2).
     * Expected: [StopListMatcher.findMatch] returns the entity for "promo" with id=2.
     */
    @Test
    fun `findMatch returns the matching entry so it can be named in the log`() {
        val keywords = stopList("sale", "promo")

        val match = matcher.findMatch("Big promo inside!", keywords)

        assertEquals("promo", match?.keyword)
        assertEquals(2L, match?.id)
    }

    /**
     * Tests that findMatch returns null when no stop-list keyword is found in the message text.
     *
     * Preconditions: Message without any stop-list keywords.
     * Expected: [StopListMatcher.findMatch] returns null.
     */
    @Test
    fun `findMatch returns null when nothing matches`() {
        assertNull(matcher.findMatch("Your appointment is confirmed", stopList("promo")))
    }

    /**
     * Tests that findMatch returns the first keyword in the list order when multiple keywords match the message.
     *
     * Preconditions: Message contains both "promo" and "sale", with keywords passed in order ["promo", "sale"].
     * Expected: [StopListMatcher.findMatch] returns "promo".
     */
    @Test
    fun `findMatch returns the first match in list order`() {
        val keywords = stopList("promo", "sale")

        val match = matcher.findMatch("promo and sale together", keywords)

        assertEquals("promo", match?.keyword)
    }

    /**
     * Tests that keywords containing spaces and spanning multiple words match properly.
     *
     * Preconditions: Stop-list keyword "special offer" against message containing "Our special offer ends soon".
     * Expected: [StopListMatcher.matches] returns true.
     */
    @Test
    fun `matches keyword spanning multiple words`() {
        assertTrue(matcher.matches("Our special offer ends soon", stopList("special offer")))
    }

    /**
     * Tests that keywords appearing on lines other than the first in a multi-line message are detected.
     *
     * Preconditions: Multi-line message with "promo" on the second line.
     * Expected: [StopListMatcher.matches] returns true.
     */
    @Test
    fun `matches keyword on a later line of a multi line message`() {
        val body = "Hello there\nBig promo inside\nThanks"

        assertTrue(matcher.matches(body, stopList("promo")))
    }
}
