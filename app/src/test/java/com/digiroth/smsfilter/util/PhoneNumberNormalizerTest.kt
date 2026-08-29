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

package com.digiroth.smsfilter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PhoneNumberNormalizer].
 *
 * Verifies address classification (standard numbers, short codes, alphanumeric IDs),
 * E.164 normalization, repliability determination, and fallback formatting.
 */
class PhoneNumberNormalizerTest {

    /**
     * Records the numbers it was asked to format and returns canned answers, so tests can both
     * stub results and assert on whether conversion was attempted.
     */
    private class FakeE164Formatter(
        private val results: Map<String, String?> = emptyMap(),
    ) : E164Formatter {
        val requestedNumbers: MutableList<String> = mutableListOf()

        override fun format(rawNumber: String, defaultRegion: String): String? {
            requestedNumbers += rawNumber
            return results[rawNumber]
        }
    }

    // ---------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------

    /**
     * Tests that a 10-digit unformatted US phone number is classified as a STANDARD sender.
     *
     * Preconditions: Input string "6505551234".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.STANDARD].
     */
    @Test
    fun `classifies plain US number as standard`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.STANDARD, normalizer.classify("6505551234"))
    }

    /**
     * Tests that a formatted US phone number with parentheses and hyphens is classified as a STANDARD sender.
     *
     * Preconditions: Input string "(650) 555-1234".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.STANDARD].
     */
    @Test
    fun `classifies formatted US number as standard`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.STANDARD, normalizer.classify("(650) 555-1234"))
    }

    /**
     * Tests that an international phone number with leading plus is classified as a STANDARD sender.
     *
     * Preconditions: Input string "+442071838750".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.STANDARD].
     */
    @Test
    fun `classifies international number as standard`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.STANDARD, normalizer.classify("+442071838750"))
    }

    /**
     * Tests that a 5-digit number is classified as a SHORT_CODE sender.
     *
     * Preconditions: Input string "89887".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.SHORT_CODE].
     */
    @Test
    fun `classifies five digit sender as short code`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.SHORT_CODE, normalizer.classify("89887"))
    }

    /**
     * Tests that a 6-digit number is classified as a SHORT_CODE sender.
     *
     * Preconditions: Input string "898870".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.SHORT_CODE].
     */
    @Test
    fun `classifies six digit sender as short code`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.SHORT_CODE, normalizer.classify("898870"))
    }

    /**
     * Tests that a 7-digit number is classified as STANDARD rather than short code.
     *
     * Preconditions: Input string "8988701".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.STANDARD].
     */
    @Test
    fun `classifies seven digit sender as standard not short code`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.STANDARD, normalizer.classify("8988701"))
    }

    /**
     * Tests that a short digit string with a leading '+' is treated as standard/international rather than short code.
     *
     * Preconditions: Input string "+89887".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.STANDARD].
     */
    @Test
    fun `classifies short digit string with country code as standard`() {
        // A leading '+' means international dialing, so this is a malformed number rather than
        // a short code — short codes are never dialed with a country code.
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.STANDARD, normalizer.classify("+89887"))
    }

    /**
     * Tests that alphabetical sender names are classified as ALPHANUMERIC.
     *
     * Preconditions: Input strings "VERIZON" and "PROMO".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.ALPHANUMERIC].
     */
    @Test
    fun `classifies alphanumeric sender id as alphanumeric`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify("VERIZON"))
        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify("PROMO"))
    }

    /**
     * Tests that mixed letters and digits are classified as ALPHANUMERIC.
     *
     * Preconditions: Input string "SHOP123".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.ALPHANUMERIC].
     */
    @Test
    fun `classifies mixed letters and digits as alphanumeric`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify("SHOP123"))
    }

    /**
     * Tests that empty or blank address strings are classified as ALPHANUMERIC to prevent auto-replies.
     *
     * Preconditions: Input strings "" and "   ".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.ALPHANUMERIC].
     */
    @Test
    fun `classifies blank address as alphanumeric so it is never replied to`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify(""))
        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify("   "))
    }

    /**
     * Tests that strings with only punctuation characters and no digits are classified as ALPHANUMERIC.
     *
     * Preconditions: Input string "+++".
     * Expected: [PhoneNumberNormalizer.classify] returns [SenderClass.ALPHANUMERIC].
     */
    @Test
    fun `classifies digitless punctuation as alphanumeric`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertEquals(SenderClass.ALPHANUMERIC, normalizer.classify("+++"))
    }

    // ---------------------------------------------------------------------
    // E.164 normalization
    // ---------------------------------------------------------------------

    /**
     * Tests that standard phone numbers are converted to E.164 via the platform formatter.
     *
     * Preconditions: Formatter configured to map "6505551234" to "+16505551234".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns "+16505551234" and records the request.
     */
    @Test
    fun `normalizes standard number using the platform formatter`() {
        val formatter = FakeE164Formatter(mapOf("6505551234" to "+16505551234"))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertEquals("+16505551234", normalizer.normalizeToE164("6505551234"))
        assertEquals(listOf("6505551234"), formatter.requestedNumbers)
    }

    /**
     * Tests that phone numbers with formatting characters are passed to the formatter and normalized.
     *
     * Preconditions: Formatter mapping "(650) 555-1234" to "+16505551234".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns "+16505551234".
     */
    @Test
    fun `normalizes number containing formatting characters`() {
        val formatter = FakeE164Formatter(mapOf("(650) 555-1234" to "+16505551234"))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertEquals("+16505551234", normalizer.normalizeToE164("(650) 555-1234"))
    }

    /**
     * Tests that international numbers are passed to the formatter and normalized.
     *
     * Preconditions: Formatter mapping "+44 20 7183 8750" to "+442071838750".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns "+442071838750".
     */
    @Test
    fun `normalizes international number`() {
        val formatter = FakeE164Formatter(mapOf("+44 20 7183 8750" to "+442071838750"))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertEquals("+442071838750", normalizer.normalizeToE164("+44 20 7183 8750"))
    }

    /**
     * Tests that when the platform formatter fails to parse an invalid number, null is returned.
     *
     * Preconditions: Formatter returning null for an unparseable number string.
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns null.
     */
    @Test
    fun `returns null when the platform cannot parse the number`() {
        val formatter = FakeE164Formatter(mapOf("12345678901234567890" to null))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertNull(normalizer.normalizeToE164("12345678901234567890"))
    }

    /**
     * Tests that a blank return value from the formatter is treated as normalization failure.
     *
     * Preconditions: Formatter returning whitespace string "   ".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns null.
     */
    @Test
    fun `treats a blank formatter result as normalization failure`() {
        val formatter = FakeE164Formatter(mapOf("6505551234" to "   "))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertNull(normalizer.normalizeToE164("6505551234"))
    }

    /**
     * Tests that short codes skip platform E.164 formatting entirely.
     *
     * Preconditions: Short code "89887".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns null and the formatter is not called.
     */
    @Test
    fun `short code skips conversion entirely`() {
        val formatter = FakeE164Formatter(mapOf("89887" to "+189887"))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertNull("a short code must not be normalized", normalizer.normalizeToE164("89887"))
        assertTrue(
            "the platform formatter must never be consulted for a short code",
            formatter.requestedNumbers.isEmpty(),
        )
    }

    /**
     * Tests that alphanumeric senders skip platform E.164 formatting entirely.
     *
     * Preconditions: Sender "VERIZON".
     * Expected: [PhoneNumberNormalizer.normalizeToE164] returns null and the formatter is not called.
     */
    @Test
    fun `alphanumeric sender skips conversion entirely`() {
        val formatter = FakeE164Formatter(mapOf("VERIZON" to "+1999"))
        val normalizer = PhoneNumberNormalizer(formatter)

        assertNull(normalizer.normalizeToE164("VERIZON"))
        assertTrue(formatter.requestedNumbers.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Repliability
    // ---------------------------------------------------------------------

    /**
     * Tests that standard phone numbers and short codes are flagged as repliable.
     *
     * Preconditions: Senders "6505551234" and "89887".
     * Expected: [PhoneNumberNormalizer.isRepliable] returns true for both.
     */
    @Test
    fun `standard numbers and short codes are repliable`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertTrue(normalizer.isRepliable("6505551234"))
        assertTrue("short codes can and must receive replies", normalizer.isRepliable("89887"))
    }

    /**
     * Tests that alphanumeric senders are flagged as non-repliable.
     *
     * Preconditions: Sender "VERIZON".
     * Expected: [PhoneNumberNormalizer.isRepliable] returns false.
     */
    @Test
    fun `alphanumeric senders are not repliable`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        assertFalse(normalizer.isRepliable("VERIZON"))
    }

    // ---------------------------------------------------------------------
    // Full normalization result
    // ---------------------------------------------------------------------

    /**
     * Tests that normalize preserves the raw address alongside formatted E.164 and digits.
     *
     * Preconditions: Formatted number "(650) 555-1234".
     * Expected: [NormalizedSender] contains rawAddress="(650) 555-1234", e164="+16505551234", digits="6505551234", isRepliable=true.
     */
    @Test
    fun `normalize retains the raw address for replies`() {
        val formatter = FakeE164Formatter(mapOf("(650) 555-1234" to "+16505551234"))
        val normalizer = PhoneNumberNormalizer(formatter)

        val result = normalizer.normalize("(650) 555-1234")

        assertEquals("(650) 555-1234", result.rawAddress)
        assertEquals("+16505551234", result.e164)
        assertEquals("6505551234", result.digits)
        assertEquals(SenderClass.STANDARD, result.senderClass)
        assertTrue(result.isRepliable)
        assertEquals("+16505551234", result.primaryLookupValue)
    }

    /**
     * Tests that normalize falls back to the raw address as primary lookup value when conversion fails.
     *
     * Preconditions: Unparseable address "555".
     * Expected: e164 is null and primaryLookupValue is "555".
     */
    @Test
    fun `normalize falls back to the raw address when conversion fails`() {
        val formatter = FakeE164Formatter(mapOf("555" to null))
        val normalizer = PhoneNumberNormalizer(formatter)

        val result = normalizer.normalize("555")

        assertNull(result.e164)
        assertEquals(
            "an unnormalizable sender must be looked up by its raw address",
            "555",
            result.primaryLookupValue,
        )
    }

    /**
     * Tests that normalize configures a short code with raw digits and repliable status.
     *
     * Preconditions: Short code "89887".
     * Expected: senderClass=SHORT_CODE, e164=null, primaryLookupValue="89887", digits="89887", isRepliable=true.
     */
    @Test
    fun `normalize looks up a short code by its raw digits`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        val result = normalizer.normalize("89887")

        assertEquals(SenderClass.SHORT_CODE, result.senderClass)
        assertNull(result.e164)
        assertEquals("89887", result.primaryLookupValue)
        assertEquals("89887", result.digits)
        assertTrue(result.isRepliable)
    }

    /**
     * Tests that normalize configures an alphanumeric sender with isRepliable=false and empty digits.
     *
     * Preconditions: Alphanumeric sender "PROMO".
     * Expected: senderClass=ALPHANUMERIC, isRepliable=false, digits="", primaryLookupValue="PROMO".
     */
    @Test
    fun `normalize marks an alphanumeric sender unrepliable`() {
        val normalizer = PhoneNumberNormalizer(FakeE164Formatter())

        val result = normalizer.normalize("PROMO")

        assertEquals(SenderClass.ALPHANUMERIC, result.senderClass)
        assertFalse(result.isRepliable)
        assertEquals("", result.digits)
        assertEquals("PROMO", result.primaryLookupValue)
    }
}
