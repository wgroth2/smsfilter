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

package com.digiroth.smsfilter.platform

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [MmsTextResolver] snippet sanitization and matching logic.
 *
 * Verifies that notification preview snippets from MMS/RCS messages with attachment
 * prefixes (e.g., "Image\n", "Photo\n") or trailing ellipses are stripped cleanly,
 * and verifies retry logic during full MMS text resolution.
 */
class MmsTextResolverUnitTest {

    private val resolver = MmsTextResolver(context = ContextWrapper(null))

    /**
     * Tests that a leading "Image\n" prefix is removed from the snippet.
     *
     * Preconditions: Input string "Image\nReply STOP to unsubscribe".
     * Expected: Sanitized output is "Reply STOP to unsubscribe".
     */
    @Test
    fun sanitizeSnippet_stripsImageNewlinePrefix() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to unsubscribe")
        assertEquals("Reply STOP to unsubscribe", result)
    }

    /**
     * Tests that a leading "Image\r\n" CRLF prefix is removed from the snippet.
     *
     * Preconditions: Input string "Image\r\nReply STOP to unsubscribe".
     * Expected: Sanitized output is "Reply STOP to unsubscribe".
     */
    @Test
    fun sanitizeSnippet_stripsImageCrLfPrefix() {
        val result = resolver.sanitizeSnippet("Image\r\nReply STOP to unsubscribe")
        assertEquals("Reply STOP to unsubscribe", result)
    }

    /**
     * Tests that a leading "Image " space-separated prefix is removed from the snippet.
     *
     * Preconditions: Input string "Image Reply STOP to cancel".
     * Expected: Sanitized output is "Reply STOP to cancel".
     */
    @Test
    fun sanitizeSnippet_stripsImageSpacePrefix() {
        val result = resolver.sanitizeSnippet("Image Reply STOP to cancel")
        assertEquals("Reply STOP to cancel", result)
    }

    /**
     * Tests that a leading "Photo\n" prefix is removed from the snippet.
     *
     * Preconditions: Input string "Photo\nSTOP to end".
     * Expected: Sanitized output is "STOP to end".
     */
    @Test
    fun sanitizeSnippet_stripsPhotoPrefix() {
        val result = resolver.sanitizeSnippet("Photo\nSTOP to end")
        assertEquals("STOP to end", result)
    }

    /**
     * Tests that trailing ASCII ellipses ("...") are stripped from truncated notification snippets.
     *
     * Preconditions: Input string "Image\nReply STOP to opt-out...".
     * Expected: Sanitized output is "Reply STOP to opt-out".
     */
    @Test
    fun sanitizeSnippet_stripsTrailingEllipsis() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to opt-out...")
        assertEquals("Reply STOP to opt-out", result)
    }

    /**
     * Tests that trailing Unicode ellipsis characters ('…') are stripped from truncated snippets.
     *
     * Preconditions: Input string "Image\nReply STOP to opt-out…".
     * Expected: Sanitized output is "Reply STOP to opt-out".
     */
    @Test
    fun sanitizeSnippet_stripsTrailingUnicodeEllipsis() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to opt-out…")
        assertEquals("Reply STOP to opt-out", result)
    }

    /**
     * Tests that plain text messages without attachment prefixes or ellipses are returned untouched.
     *
     * Preconditions: Input string "STOP".
     * Expected: Sanitized output is "STOP".
     */
    @Test
    fun sanitizeSnippet_handlesPlainMessageWithoutPrefix() {
        val result = resolver.sanitizeSnippet("STOP")
        assertEquals("STOP", result)
    }

    /**
     * Tests that a snippet consisting solely of an attachment label returns an empty string.
     *
     * Preconditions: Input string "Image".
     * Expected: Sanitized output is "".
     */
    @Test
    fun sanitizeSnippet_returnsEmptyForOnlyImage() {
        val result = resolver.sanitizeSnippet("Image")
        assertEquals("", result)
    }

    /**
     * Tests that resolveFullMmsTextWithRetry retries until text becomes available and returns the resolved text.
     *
     * Preconditions: Overridden resolver returning null on first attempt and resolved text on second attempt.
     * Expected: Returns "Full resolved MMS text" and records exactly 2 attempts.
     */
    @Test
    fun resolveFullMmsTextWithRetry_retriesAndReturnsResolvedTextWhenAvailable() = kotlinx.coroutines.test.runTest {
        val retryResolver = object : MmsTextResolver(ContextWrapper(null)) {
            var callCount = 0
            override fun resolveFullMmsText(prefixSnippet: String?): String? {
                callCount++
                return if (callCount >= 2) "Full resolved MMS text" else null
            }
        }

        val result = retryResolver.resolveFullMmsTextWithRetry(prefixSnippet = "STOP", maxAttempts = 3, delayMillis = 10L)
        assertEquals("Full resolved MMS text", result)
        assertEquals(2, retryResolver.callCount)
    }

    /**
     * Tests that resolveFullMmsTextWithRetry returns null when all retry attempts fail.
     *
     * Preconditions: Overridden resolver returning null on all attempts.
     * Expected: Returns null and records exactly maxAttempts (3) invocations.
     */
    @Test
    fun resolveFullMmsTextWithRetry_returnsNullWhenAllAttemptsFail() = kotlinx.coroutines.test.runTest {
        val failingResolver = object : MmsTextResolver(ContextWrapper(null)) {
            var callCount = 0
            override fun resolveFullMmsText(prefixSnippet: String?): String? {
                callCount++
                return null
            }
        }

        val result = failingResolver.resolveFullMmsTextWithRetry(prefixSnippet = "STOP", maxAttempts = 3, delayMillis = 10L)
        assertNull(result)
        assertEquals(3, failingResolver.callCount)
    }

    // ---------------------------------------------------------------------
    // Part selection — which stored MMS text a notification is actually about
    // ---------------------------------------------------------------------

    /**
     * Tests that a snippet carrying no usable text resolves to nothing rather than to the newest part.
     *
     * This is the regression guard for the defect where a caption-less image notification adopted
     * whatever text part happened to be newest on the device, from any conversation.
     *
     * Preconditions: Candidate parts exist; snippet is null.
     * Expected: Returns null.
     */
    @Test
    fun selectMatchingPart_returnsNullForNullSnippet() {
        val parts = listOf("An unrelated message from someone else", "Older still")
        assertNull(resolver.selectMatchingPart(parts, null))
    }

    /**
     * Tests that a snippet of exactly "Image" resolves to nothing.
     *
     * Preconditions: Candidate parts exist; snippet sanitizes to the empty string.
     * Expected: Returns null.
     */
    @Test
    fun selectMatchingPart_returnsNullWhenSnippetSanitizesToBlank() {
        val parts = listOf("An unrelated message from someone else")
        assertNull(resolver.selectMatchingPart(parts, "Image"))
    }

    /**
     * Tests that the part matching the snippet is returned rather than the newest part.
     *
     * Preconditions: Newest part is unrelated; a later entry matches the snippet.
     * Expected: Returns the matching entry.
     */
    @Test
    fun selectMatchingPart_prefersTheMatchOverTheNewestPart() {
        val parts = listOf(
            "Dinner at seven?",
            "Flash sale ends tonight. Reply STOP to unsubscribe",
        )
        val result = resolver.selectMatchingPart(parts, "Flash sale ends tonight")
        assertEquals("Flash sale ends tonight. Reply STOP to unsubscribe", result)
    }

    /**
     * Tests that a match deeper in the list is found once nearer non-matching parts are skipped.
     *
     * Preconditions: Three newer unrelated parts precede the match.
     * Expected: Returns the matching entry.
     */
    @Test
    fun selectMatchingPart_scansPastNonMatchingParts() {
        val parts = listOf(
            "Unrelated one",
            "Unrelated two",
            "Unrelated three",
            "Your order shipped. Reply STOP to opt out",
        )
        val result = resolver.selectMatchingPart(parts, "Your order shipped")
        assertEquals("Your order shipped. Reply STOP to opt out", result)
    }

    /**
     * Tests that a snippet matching nothing resolves to null even when parts are present.
     *
     * Preconditions: Non-empty part list, none related to the snippet.
     * Expected: Returns null.
     */
    @Test
    fun selectMatchingPart_returnsNullWhenNothingMatches() {
        val parts = listOf("Dinner at seven?", "Running late")
        assertNull(resolver.selectMatchingPart(parts, "Flash sale ends tonight"))
    }

    /**
     * Tests that a non-breaking space in the stored part still matches a plain space in the snippet.
     *
     * Marketing MMS routinely contain U+00A0, which does not survive the notification round-trip
     * identically; without normalization the real message would fail to resolve.
     *
     * Preconditions: Stored part uses U+00A0 where the snippet uses a plain space.
     * Expected: Returns the stored part.
     */
    @Test
    fun selectMatchingPart_matchesAcrossNonBreakingSpaces() {
        val stored = "Flash\u00A0sale ends tonight. Reply STOP to unsubscribe"
        val result = resolver.selectMatchingPart(listOf(stored), "Flash sale ends tonight")
        assertEquals(stored, result)
    }

    /**
     * Tests that an empty candidate list resolves to null.
     *
     * Preconditions: No parts were read from the provider; snippet is usable.
     * Expected: Returns null.
     */
    @Test
    fun selectMatchingPart_returnsNullForEmptyPartList() {
        assertNull(resolver.selectMatchingPart(emptyList(), "Flash sale ends tonight"))
    }
}
