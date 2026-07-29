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
 * Keyword lists are constructed inline and passed as arguments — the matcher takes no DAO, so
 * nothing here needs a database or a mock.
 */
class StopListMatcherTest {

    private val matcher = StopListMatcher()

    private fun stopList(vararg keywords: String): List<StopListEntity> =
        keywords.mapIndexed { index, keyword ->
            StopListEntity(id = index + 1L, keyword = keyword)
        }

    @Test
    fun `matches keyword appearing in the message`() {
        val keywords = stopList("promo")

        assertTrue(matcher.matches("Big promo inside!", keywords))
    }

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

    @Test
    fun `keywords match as substrings inside longer words`() {
        // Documented behaviour: the stop list is a coarse filter, and over-matching only means a
        // message is left alone, which is the safe direction.
        assertTrue(matcher.matches("This is promotional content", stopList("promo")))
    }

    @Test
    fun `does not match when no keyword is present`() {
        assertFalse(matcher.matches("Your appointment is confirmed", stopList("promo", "sale")))
    }

    @Test
    fun `empty keyword list never matches`() {
        assertFalse(matcher.matches("Big promo inside!", emptyList()))
    }

    @Test
    fun `empty message never matches`() {
        assertFalse(matcher.matches("", stopList("promo")))
    }

    @Test
    fun `blank keyword is ignored rather than matching everything`() {
        // An empty string is a substring of every string, so a blank row would otherwise silence
        // the entire app.
        assertFalse(matcher.matches("Any message at all", stopList("")))
        assertFalse(matcher.matches("Any message at all", stopList("   ")))
    }

    @Test
    fun `blank keyword does not mask a real keyword later in the list`() {
        val keywords = stopList("", "promo")

        val match = matcher.findMatch("Big promo inside!", keywords)

        assertEquals("promo", match?.keyword)
    }

    @Test
    fun `findMatch returns the matching entry so it can be named in the log`() {
        val keywords = stopList("sale", "promo")

        val match = matcher.findMatch("Big promo inside!", keywords)

        assertEquals("promo", match?.keyword)
        assertEquals(2L, match?.id)
    }

    @Test
    fun `findMatch returns null when nothing matches`() {
        assertNull(matcher.findMatch("Your appointment is confirmed", stopList("promo")))
    }

    @Test
    fun `findMatch returns the first match in list order`() {
        val keywords = stopList("promo", "sale")

        val match = matcher.findMatch("promo and sale together", keywords)

        assertEquals("promo", match?.keyword)
    }

    @Test
    fun `matches keyword spanning multiple words`() {
        assertTrue(matcher.matches("Our special offer ends soon", stopList("special offer")))
    }

    @Test
    fun `matches keyword on a later line of a multi line message`() {
        val body = "Hello there\nBig promo inside\nThanks"

        assertTrue(matcher.matches(body, stopList("promo")))
    }
}
