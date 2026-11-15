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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TextMatching], the matching primitives shared by the stop list and the
 * opt-out detector.
 */
class TextMatchingTest {

    /**
     * Tests the motivating case: a whole-word keyword must not match inside a longer word.
     *
     * Preconditions: Needle "avis", haystack containing "Davis".
     * Expected: No whole-word match; a plain substring test would match.
     */
    @Test
    fun `whole word does not match inside a longer word`() {
        assertFalse(TextMatching.containsWholeWord("Call Davis today", "avis", ignoreCase = true))
        assertFalse(TextMatching.containsWholeWord("Avisa al cliente", "avis", ignoreCase = true))
    }

    /**
     * Tests that punctuation, spaces, and the ends of the text all count as word boundaries.
     *
     * Preconditions: "Avis" surrounded by various boundaries.
     * Expected: Every case matches.
     */
    @Test
    fun `whole word matches between spaces punctuation and text edges`() {
        listOf("Avis", "Rent from Avis.", "(Avis) deals", "Avis, Hertz", "Avis's offer", "-Avis-").forEach { text ->
            assertTrue("expected a match in \"$text\"", TextMatching.containsWholeWord(text, "avis", ignoreCase = true))
        }
    }

    /**
     * Tests that a later whole-word occurrence is found after an earlier embedded one.
     *
     * Preconditions: Haystack "Davis and Avis", where only the second occurrence is a whole word.
     * Expected: Match.
     */
    @Test
    fun `whole word examines every occurrence`() {
        assertTrue(TextMatching.containsWholeWord("Davis and Avis", "avis", ignoreCase = true))
    }

    /**
     * Tests that digits count as word characters, preserving the opt-out detector's behaviour.
     *
     * Preconditions: Needle "stop", haystack "stop2stop".
     * Expected: No whole-word match.
     */
    @Test
    fun `digits are word characters`() {
        assertFalse(TextMatching.containsWholeWord("stop2stop", "stop", ignoreCase = true))
    }

    /**
     * Tests the case-sensitivity flag in both matching modes.
     *
     * Preconditions: Needle "Avis" against "AVIS" and "Avis".
     * Expected: Case-sensitive matching requires identical capitalization; insensitive does not.
     */
    @Test
    fun `case sensitivity applies to both modes`() {
        assertFalse(TextMatching.contains("Rent from AVIS", "Avis", ignoreCase = false, wholeWord = true))
        assertFalse(TextMatching.contains("Rent from AVIS", "Avis", ignoreCase = false, wholeWord = false))
        assertTrue(TextMatching.contains("Rent from Avis", "Avis", ignoreCase = false, wholeWord = true))
        assertTrue(TextMatching.contains("Rent from AVIS", "Avis", ignoreCase = true, wholeWord = true))
    }

    /**
     * Tests that substring mode behaves exactly like the previous stop-list matching.
     *
     * Preconditions: Needle "avis" in substring mode against "Davis".
     * Expected: Match.
     */
    @Test
    fun `substring mode matches inside longer words`() {
        assertTrue(TextMatching.contains("Call Davis today", "avis", ignoreCase = true, wholeWord = false))
    }

    /**
     * Tests that a whole-word needle containing punctuation and spaces is bounded at its outer edges.
     *
     * Preconditions: Needles "PG&E" and "Rebecca Cooke".
     * Expected: Match when bounded; no match when the outer edge runs into a letter.
     */
    @Test
    fun `whole word with punctuation and multi word needles`() {
        assertTrue(TextMatching.containsWholeWord("PG&E: Bill ready", "pg&e", ignoreCase = true))
        assertFalse(TextMatching.containsWholeWord("XPG&E bill", "pg&e", ignoreCase = true))
        assertTrue(TextMatching.containsWholeWord("like Rebecca Cooke in WI-03", "rebecca cooke", ignoreCase = true))
    }

    /**
     * Tests that an empty needle never matches in either mode.
     *
     * Preconditions: Empty needle.
     * Expected: No match.
     */
    @Test
    fun `empty needle never matches`() {
        assertFalse(TextMatching.contains("anything", "", ignoreCase = true, wholeWord = false))
        assertFalse(TextMatching.contains("anything", "", ignoreCase = true, wholeWord = true))
    }

    /**
     * Tests that decorative Unicode letters fold to plain letters, preserving case.
     *
     * Preconditions: Mathematical sans-serif bold, sans-serif italic, and full-width text.
     * Expected: Each normalizes to the plain ASCII equivalent.
     */
    @Test
    fun `styled unicode letters normalize to plain letters`() {
        assertEquals("Cooke", TextMatching.normalizeStyledText("\uD835\uDDD6\uD835\uDDFC\uD835\uDDFC\uD835\uDDF8\uD835\uDDF2"))
        assertEquals("please", TextMatching.normalizeStyledText("\uD835\uDE31\uD835\uDE2D\uD835\uDE26\uD835\uDE22\uD835\uDE34\uD835\uDE26"))
        assertEquals("Cooke", TextMatching.normalizeStyledText("\uFF23\uFF4F\uFF4F\uFF4B\uFF45"))
    }

    /**
     * Tests that the non-breaking space normalizes to a regular space.
     *
     * Preconditions: "Rebecca\u00A0Cooke".
     * Expected: "Rebecca Cooke".
     */
    @Test
    fun `non breaking space normalizes to a regular space`() {
        assertEquals("Rebecca Cooke", TextMatching.normalizeStyledText("Rebecca\u00A0Cooke"))
    }

    /**
     * Tests that plain text is unchanged by normalization.
     *
     * Preconditions: An ordinary ASCII message.
     * Expected: Identical output.
     */
    @Test
    fun `plain text is unchanged by normalization`() {
        val text = "PG&E: Bill ready for Acct. Txt STOP to cancel"
        assertEquals(text, TextMatching.normalizeStyledText(text))
    }
}
