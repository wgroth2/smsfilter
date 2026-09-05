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
import com.digiroth.smsfilter.data.db.entity.ReplyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [OptOutDetector].
 *
 * Pattern lists are constructed inline and passed as arguments; the detector takes no DAO, so
 * nothing here needs a database or a mock. [defaultPatterns] mirrors the four rows seeded into a
 * fresh database, and the final group of tests uses entirely custom patterns to prove the
 * detector honours [MatchMode] generically rather than special-casing the seeded strings.
 */
class OptOutDetectorTest {

    private val detector = OptOutDetector()

    private fun pattern(
        text: String,
        replyType: ReplyType,
        matchMode: MatchMode,
    ): OptOutPatternEntity = OptOutPatternEntity(
        pattern = text,
        replyType = replyType,
        matchMode = matchMode,
    )

    /** The default patterns the database seeds on first creation. */
    private val defaultPatterns: List<OptOutPatternEntity> = listOf(
        pattern("stop to quit", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to end", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to opt out", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to opt-out", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to cancel", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to unsubscribe", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to optout", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop2stop", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop2quit", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop2end", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop=end", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("end2end", ReplyType.END, MatchMode.ANYWHERE),
        pattern("end to end", ReplyType.END, MatchMode.ANYWHERE),
        pattern("end2stop", ReplyType.END, MatchMode.ANYWHERE),
        pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_EXACT),
        pattern("end", ReplyType.END, MatchMode.LAST_LINE_EXACT),
    )

    // ---------------------------------------------------------------------
    // ANYWHERE matching
    // ---------------------------------------------------------------------

    /**
     * Tests detection of "stop2stop" embedded inside a message body using ANYWHERE mode.
     *
     * Preconditions: Message "stop2stop this deal" tested against default patterns.
     * Expected: [OptOutDetector.detect] returns matched pattern "stop2stop" with reply type STOP and keyword "stop".
     */
    @Test
    fun `detects stop2stop mid message`() {
        val result = detector.detect("stop2stop this deal", defaultPatterns)

        assertEquals("stop2stop", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "end2end" embedded inside a message body using ANYWHERE mode.
     *
     * Preconditions: Message "end2end encryption rocks" tested against default patterns.
     * Expected: [OptOutDetector.detect] returns matched pattern "end2end" with reply type END and keyword "end".
     */
    @Test
    fun `detects end2end mid message`() {
        val result = detector.detect("end2end encryption rocks", defaultPatterns)

        assertEquals("end2end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
        assertEquals("end", result?.replyKeyword)
    }

    /**
     * Tests that ANYWHERE matching is case-insensitive for uppercase and mixed-case messages.
     *
     * Preconditions: Messages "STOP2STOP THIS DEAL" and "Stop2Stop this deal".
     * Expected: [OptOutDetector.detect] returns non-null detections for both.
     */
    @Test
    fun `detects anywhere pattern regardless of case`() {
        assertNotNull(detector.detect("STOP2STOP THIS DEAL", defaultPatterns))
        assertNotNull(detector.detect("Stop2Stop this deal", defaultPatterns))
    }

    /**
     * Tests detection of an ANYWHERE pattern occurring on the first line of a multi-line message.
     *
     * Preconditions: Message "end2end\nSee the attached details".
     * Expected: [OptOutDetector.detect] returns matched pattern "end2end".
     */
    @Test
    fun `detects anywhere pattern on a subject style first line`() {
        val body = "end2end\nSee the attached details"

        assertEquals("end2end", detector.detect(body, defaultPatterns)?.pattern)
    }

    /**
     * Tests detection of "stop to cancel" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Flash sale! Text STOP to Cancel alerts".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to cancel" with reply type STOP.
     */
    @Test
    fun `detects stop to cancel mid message`() {
        val result = detector.detect("Flash sale! Text STOP to Cancel alerts", defaultPatterns)

        assertEquals("stop to cancel", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of hyphenated "stop to opt-out" mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Weekly specials: Reply STOP to opt-out anytime".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to opt-out" with reply type STOP.
     */
    @Test
    fun `detects stop to opt-out mid message`() {
        val result = detector.detect("Weekly specials: Reply STOP to opt-out anytime", defaultPatterns)

        assertEquals("stop to opt-out", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of space-separated "stop to opt out" mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Promo info. Reply STOP to opt out".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to opt out" with reply type STOP.
     */
    @Test
    fun `detects stop to opt out mid message`() {
        val result = detector.detect("Promo info. Reply STOP to opt out", defaultPatterns)

        assertEquals("stop to opt out", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop to end" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "STOP to end account texts".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to end" with reply type STOP.
     */
    @Test
    fun `detects stop to end mid message`() {
        val result = detector.detect("STOP to end account texts", defaultPatterns)

        assertEquals("stop to end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop to quit" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Updates: text STOP to quit promotions".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to quit" with reply type STOP.
     */
    @Test
    fun `detects stop to quit mid message`() {
        val result = detector.detect("Updates: text STOP to quit promotions", defaultPatterns)

        assertEquals("stop to quit", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop=end" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Latest discounts. Text STOP=END to unsubscribe".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop=end" with reply type STOP.
     */
    @Test
    fun `detects stop=end mid message`() {
        val result = detector.detect("Latest discounts. Text STOP=END to unsubscribe", defaultPatterns)

        assertEquals("stop=end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop to unsubscribe" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Weekly alerts. Reply STOP to unsubscribe.".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to unsubscribe" with reply type STOP.
     */
    @Test
    fun `detects stop to unsubscribe mid message`() {
        val result = detector.detect("Weekly alerts. Reply STOP to unsubscribe.", defaultPatterns)

        assertEquals("stop to unsubscribe", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop to optout" unhyphenated phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Deals: text STOP to optout today".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop to optout" with reply type STOP.
     */
    @Test
    fun `detects stop to optout mid message`() {
        val result = detector.detect("Deals: text STOP to optout today", defaultPatterns)

        assertEquals("stop to optout", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop2quit" pattern mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Campaign update. Stop2Quit".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop2quit" with reply type STOP.
     */
    @Test
    fun `detects stop2quit mid message`() {
        val result = detector.detect("Campaign update. Stop2Quit", defaultPatterns)

        assertEquals("stop2quit", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests detection of "stop2end" pattern mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Election alerts. Stop2End".
     * Expected: [OptOutDetector.detect] returns matched pattern "stop2end" with reply type STOP.
     */
    @Test
    fun `detects stop2end mid message`() {
        val result = detector.detect("Election alerts. Stop2End", defaultPatterns)

        assertEquals("stop2end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Regression test for a real message that went unanswered in the field.
     *
     * A political campaign sent a multi-line RCS message whose final line was the single token
     * "Stop2End". No reply was sent, because the only patterns installed at the time were bare
     * "stop" (last-line-exact, so "Stop2End" is not an exact match) and bare "end"
     * (last-line-contains, which treats digits as word characters, so "Stop2End" is one token and
     * not the word "end"). Neither rule was wrong; the phrasing simply had no pattern.
     *
     * Preconditions: The full original message body, ending in "Stop2End".
     * Expected: [OptOutDetector.detect] matches "stop2end" and yields the "stop" reply keyword.
     */
    @Test
    fun `detects stop2end as the last line of a real campaign message`() {
        val body = """
            re: Donald J. Trump's crimes

            Should Congress invoke the 25th Amendment and REMOVE Trump from office?

            Respond YES or NO: dem-action.org/l/vMm6je

            DAC
            Stop2End
        """.trimIndent()

        val result = detector.detect(body, defaultPatterns)

        assertEquals("stop2end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    /**
     * Tests that bare "stop" and bare "end" alone cannot rescue a "Stop2End" sign-off.
     *
     * This is the exact pattern set that was live on the device when the message above was missed,
     * and it pins down *why* it was missed — so that if someone later loosens the boundary rules,
     * this test explains what the old behaviour was rather than silently flipping.
     *
     * Preconditions: Only the two bare keyword patterns, applied to a body ending in "Stop2End".
     * Expected: [OptOutDetector.detect] returns `null`.
     */
    @Test
    fun `bare stop and end patterns do not match a Stop2End sign-off`() {
        val bareOnly = listOf(
            pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_EXACT),
            pattern("end", ReplyType.END, MatchMode.LAST_LINE_CONTAINS),
        )

        val result = detector.detect("DAC\nStop2End", bareOnly)

        assertNull("digits bind the token together, so neither bare keyword applies", result)
    }

    /**
     * Tests detection of "end2stop" pattern mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Daily newsletter. end2stop".
     * Expected: [OptOutDetector.detect] returns matched pattern "end2stop" with reply type END.
     */
    @Test
    fun `detects end2stop mid message`() {
        val result = detector.detect("Daily newsletter. end2stop", defaultPatterns)

        assertEquals("end2stop", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
        assertEquals("end", result?.replyKeyword)
    }

    /**
     * Tests detection of "end to end" phrase mid-message using ANYWHERE mode.
     *
     * Preconditions: Message "Notifications active. Reply end to end to stop.".
     * Expected: [OptOutDetector.detect] returns matched pattern "end to end" with reply type END.
     */
    @Test
    fun `detects end to end mid message`() {
        val result = detector.detect("Notifications active. Reply end to end to stop.", defaultPatterns)

        assertEquals("end to end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
        assertEquals("end", result?.replyKeyword)
    }

    // ---------------------------------------------------------------------
    // LAST_LINE_CONTAINS matching
    //
    // Bodies here are taken verbatim from real messages captured on a test device, because the
    // point of this mode is to handle wording that actually occurs rather than wording that is
    // convenient to match.
    // ---------------------------------------------------------------------

    /**
     * Tests that LAST_LINE_CONTAINS matches whole-word "stop" in a footer phrase that LAST_LINE_EXACT misses.
     *
     * Preconditions: Footer ending in "STOP to quit".
     * Expected: LAST_LINE_EXACT pattern returns null; LAST_LINE_CONTAINS pattern returns matched pattern "stop".
     */
    @Test
    fun `matches the reply stop to quit footer that last-line-exact misses`() {
        val body = "San Jose Clin Trials: Join a research study. Text \"YES\" to learn more " +
            "or contact us at 408-443-3542 Reply YES to learn more, STOP to quit"
        val exact = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_EXACT))
        val contains = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull("the exact rule cannot see this", detector.detect(body, exact))
        assertEquals("stop", detector.detect(body, contains)?.pattern)
    }

    /**
     * Tests that LAST_LINE_CONTAINS matches a keyword followed immediately by punctuation.
     *
     * Preconditions: Message ending in "STOP to cancel.".
     * Expected: [OptOutDetector.detect] matches "stop".
     */
    @Test
    fun `matches a keyword followed immediately by punctuation`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals("stop", detector.detect("Deal\nReply HELP for help, STOP to cancel.", patterns)?.pattern)
    }

    /**
     * Tests that LAST_LINE_CONTAINS does not match "end" as a substring within words like "weekend".
     *
     * Preconditions: Message "Your order shipped\nHave a great weekend!".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `does not match end inside weekend`() {
        // The single most important negative case. A plain substring test would reply "end" to a
        // cheerful sign-off, which is exactly the failure this mode has to avoid being.
        val patterns = listOf(pattern("end", ReplyType.END, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Your order shipped\nHave a great weekend!", patterns))
    }

    /**
     * Tests that LAST_LINE_CONTAINS does not match "stop" embedded in a domain name.
     *
     * Preconditions: Message "Sale today\nVisit stopandshop.com".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `does not match stop inside a domain name`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Sale today\nVisit stopandshop.com", patterns))
    }

    /**
     * Tests that LAST_LINE_CONTAINS does not match concatenated keywords without boundary spaces.
     *
     * Preconditions: Footer containing "ReplySTOPtoCancel".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `does not match a keyword run together with surrounding words`() {
        // Real AARP footer. Documented as out of scope for this mode: no boundary either side.
        val body = "AARP Advocates: Keep strengthening Medicare price negotiations. " +
            "Take a look: aarp.info/RxReport ReplySTOPtoCancel"
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect(body, patterns))
    }

    /**
     * Tests that LAST_LINE_CONTAINS finds a standalone keyword later on the line even if preceded by an embedded non-match.
     *
     * Preconditions: Last line "stopandshop.com deals - reply STOP to end".
     * Expected: [OptOutDetector.detect] finds the standalone "stop" keyword.
     */
    @Test
    fun `finds a later whole-word occurrence when an earlier one is embedded`() {
        // Scanning must not give up on the first hit: "stopandshop" comes first and is not a word.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals(
            "stop",
            detector.detect("Offer\nstopandshop.com deals - reply STOP to end", patterns)?.pattern,
        )
    }

    /**
     * Tests that LAST_LINE_CONTAINS inspects only the final non-blank line.
     *
     * Preconditions: Keyword appears on the first line, followed by another non-matching line.
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `only searches the last line`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Reply STOP to unsubscribe\nThanks for shopping", patterns))
    }

    /**
     * Tests that LAST_LINE_CONTAINS does not match digit-adjacent keywords like "stop2stop".
     *
     * Preconditions: Message ending in "reply stop2stop now".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `does not match a digit-adjacent keyword`() {
        // stop2stop is a separate ANYWHERE pattern and must not be claimed by this mode.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Deal\nreply stop2stop now", patterns))
    }

    /**
     * Tests that LAST_LINE_CONTAINS still matches when the keyword comprises the entire last line.
     *
     * Preconditions: Message "Hello\nSTOP".
     * Expected: [OptOutDetector.detect] matches "stop".
     */
    @Test
    fun `still matches when the keyword is the entire last line`() {
        // The mode must be a strict superset of LAST_LINE_EXACT, or switching a pattern over to it
        // would lose detections that used to work.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals("stop", detector.detect("Hello\nSTOP", patterns)?.pattern)
    }

    /**
     * Tests that LAST_LINE_CONTAINS returns the correct match mode and reply type on the result.
     *
     * Preconditions: Message "Hi\nreply STOP to quit".
     * Expected: Result contains [MatchMode.LAST_LINE_CONTAINS] and [ReplyType.STOP].
     */
    @Test
    fun `reports the contains mode on the result`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))
        val result = detector.detect("Hi\nreply STOP to quit", patterns)

        assertEquals(MatchMode.LAST_LINE_CONTAINS, result?.matchMode)
        assertEquals(ReplyType.STOP, result?.replyType)
    }

    /**
     * Tests that a blank pattern in LAST_LINE_CONTAINS mode never matches any text.
     *
     * Preconditions: Pattern "   " in LAST_LINE_CONTAINS mode.
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `blank contains pattern never matches`() {
        val patterns = listOf(pattern("   ", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Hello\nreply STOP to unsubscribe", patterns))
    }

    // ---------------------------------------------------------------------
    // LAST_LINE_EXACT matching
    // ---------------------------------------------------------------------

    /**
     * Tests detection of a bare "STOP" isolated on the last line under LAST_LINE_EXACT mode.
     *
     * Preconditions: Message "Hello\nSTOP" tested against default patterns.
     * Expected: Returns pattern "stop", matchMode LAST_LINE_EXACT, and replyType STOP.
     */
    @Test
    fun `detects bare stop alone on the last line`() {
        val result = detector.detect("Hello\nSTOP", defaultPatterns)

        assertEquals("stop", result?.pattern)
        assertEquals(MatchMode.LAST_LINE_EXACT, result?.matchMode)
        assertEquals(ReplyType.STOP, result?.replyType)
    }

    /**
     * Tests detection of a bare "End" on the last line regardless of casing.
     *
     * Preconditions: Message "Thanks\nEnd".
     * Expected: Returns pattern "end" and replyType END.
     */
    @Test
    fun `detects bare end alone on the last line regardless of case`() {
        val result = detector.detect("Thanks\nEnd", defaultPatterns)

        assertEquals("end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
    }

    /**
     * Tests detection of a lowercase "stop" on the last line.
     *
     * Preconditions: Message "Thanks\nstop".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `detects lowercase stop alone on the last line`() {
        assertEquals("stop", detector.detect("Thanks\nstop", defaultPatterns)?.pattern)
    }

    /**
     * Tests that LAST_LINE_EXACT ignores single-line messages where "STOP" is embedded in copy.
     *
     * Preconditions: Message "Hello, please stop by our store today".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `ignores stop embedded in marketing copy on a single line`() {
        // The whole reason bare keywords are last-line-exact: this phrasing appears in ordinary
        // marketing copy and must never trigger a reply.
        assertNull(detector.detect("Hello, please stop by our store today", defaultPatterns))
    }

    /**
     * Tests that LAST_LINE_EXACT ignores words containing "stop" on non-final lines.
     *
     * Preconditions: Message "Postop care instructions\nCall us".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `ignores stop embedded in a word on a non final line`() {
        assertNull(detector.detect("Postop care instructions\nCall us", defaultPatterns))
    }

    /**
     * Tests that LAST_LINE_EXACT ignores words like "Postop" even when appearing on the last line.
     *
     * Preconditions: Message "Care instructions\nPostop".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `ignores stop embedded in a word even on the last line`() {
        // "Postop" is not exactly "stop", so LAST_LINE_EXACT must not fire.
        assertNull(detector.detect("Care instructions\nPostop", defaultPatterns))
    }

    /**
     * Tests that LAST_LINE_EXACT ignores a last line containing other words alongside the keyword.
     *
     * Preconditions: Message "Hello\nPlease stop messaging me".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `ignores last line that merely contains stop among other words`() {
        assertNull(detector.detect("Hello\nPlease stop messaging me", defaultPatterns))
    }

    /**
     * Tests that LAST_LINE_EXACT matches when the last line has leading/trailing ASCII whitespace.
     *
     * Preconditions: Message "Hello\n  STOP  ".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `detects stop on the last line with surrounding spaces`() {
        assertEquals("stop", detector.detect("Hello\n  STOP  ", defaultPatterns)?.pattern)
    }

    /**
     * Tests that LAST_LINE_EXACT identifies the last non-blank line when followed by empty lines.
     *
     * Preconditions: Message "Hello\nSTOP\n\n   \n".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `detects stop on the last non blank line when trailing blank lines follow`() {
        val body = "Hello\nSTOP\n\n   \n"

        assertEquals("stop", detector.detect(body, defaultPatterns)?.pattern)
    }

    /**
     * Tests that LAST_LINE_EXACT handles non-breaking spaces (U+00A0) and em-spaces (U+2003).
     *
     * Preconditions: Messages with unicode spaces around "STOP" and "stop".
     * Expected: Returns pattern "stop" for both unicode whitespace variants.
     */
    @Test
    fun `detects stop when padded with unicode space separators`() {
        // U+00A0 (non-breaking space) and U+2003 (em space) both occur in real marketing SMS.
        // Character.isWhitespace returns false for U+00A0, so relying on trim() alone would miss
        // this and silently drop a legitimate opt-out.
        val body = "Hello\n STOP "

        assertEquals("stop", detector.detect(body, defaultPatterns)?.pattern)
        assertEquals("stop", detector.detect("Hello\n stop ", defaultPatterns)?.pattern)
    }

    /**
     * Tests that a trailing line consisting solely of non-breaking space (U+00A0) is treated as blank.
     *
     * Preconditions: Message "Hello\nSTOP\n ".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `treats a unicode space only trailing line as blank`() {
        val body = "Hello\nSTOP\n "

        assertEquals(
            "a non-breaking-space line must not become the last line",
            "stop",
            detector.detect(body, defaultPatterns)?.pattern,
        )
    }

    /**
     * Tests that CRLF ("\r\n") line endings are correctly parsed.
     *
     * Preconditions: Message "Hello\r\nSTOP".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `handles carriage return line endings`() {
        assertEquals("stop", detector.detect("Hello\r\nSTOP", defaultPatterns)?.pattern)
    }

    /**
     * Tests that a single-line message consisting only of the keyword matches LAST_LINE_EXACT.
     *
     * Preconditions: Message "STOP".
     * Expected: Returns pattern "stop".
     */
    @Test
    fun `detects stop in a single line message that is only the keyword`() {
        assertEquals("stop", detector.detect("STOP", defaultPatterns)?.pattern)
    }

    // ---------------------------------------------------------------------
    // Degenerate input
    // ---------------------------------------------------------------------

    /**
     * Tests that an empty message body returns null.
     *
     * Preconditions: Empty string "".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `empty message never matches`() {
        assertNull(detector.detect("", defaultPatterns))
    }

    /**
     * Tests that a whitespace-only message body returns null.
     *
     * Preconditions: Message "   \n\n  ".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `whitespace only message never matches`() {
        assertNull(detector.detect("   \n\n  ", defaultPatterns))
    }

    /**
     * Tests that an empty pattern list returns null for any message.
     *
     * Preconditions: Message "Hello\nSTOP" against empty pattern list.
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `empty pattern list never matches`() {
        assertNull(detector.detect("Hello\nSTOP", emptyList()))
    }

    /**
     * Tests that a blank pattern is ignored and does not match arbitrary messages.
     *
     * Preconditions: Pattern with empty text "".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `blank pattern is ignored rather than matching every message`() {
        val patterns = listOf(pattern("", ReplyType.STOP, MatchMode.ANYWHERE))

        assertNull(detector.detect("Any message at all", patterns))
    }

    /**
     * Tests that a blank pattern in the list does not mask subsequent valid patterns.
     *
     * Preconditions: Patterns ["   ", "stop2stop"].
     * Expected: [OptOutDetector.detect] matches "stop2stop".
     */
    @Test
    fun `blank pattern does not mask a real pattern later in the list`() {
        val patterns = listOf(
            pattern("   ", ReplyType.STOP, MatchMode.ANYWHERE),
            pattern("stop2stop", ReplyType.STOP, MatchMode.ANYWHERE),
        )

        assertEquals("stop2stop", detector.detect("stop2stop now", patterns)?.pattern)
    }

    // ---------------------------------------------------------------------
    // Generic matchMode handling (no special-casing of seeded strings)
    // ---------------------------------------------------------------------

    /**
     * Tests that custom user-defined patterns with ANYWHERE mode match mid-message.
     *
     * Preconditions: Pattern "unsubscribe" with ANYWHERE mode against "Click to unsubscribe from our list".
     * Expected: Returns pattern "unsubscribe" with MatchMode.ANYWHERE.
     */
    @Test
    fun `custom anywhere pattern matches mid message`() {
        val patterns = listOf(pattern("unsubscribe", ReplyType.STOP, MatchMode.ANYWHERE))

        val result = detector.detect("Click to unsubscribe from our list", patterns)

        assertEquals("unsubscribe", result?.pattern)
        assertEquals(MatchMode.ANYWHERE, result?.matchMode)
    }

    /**
     * Tests that custom user-defined patterns with LAST_LINE_EXACT do not match mid-message.
     *
     * Preconditions: Pattern "quit" with LAST_LINE_EXACT against "Please quit sending these".
     * Expected: [OptOutDetector.detect] returns null.
     */
    @Test
    fun `custom last line exact pattern does not match mid message`() {
        val patterns = listOf(pattern("quit", ReplyType.END, MatchMode.LAST_LINE_EXACT))

        assertNull(detector.detect("Please quit sending these", patterns))
    }

    /**
     * Tests that custom user-defined patterns with LAST_LINE_EXACT match when isolated on the last line.
     *
     * Preconditions: Pattern "quit" with LAST_LINE_EXACT against "Enough already\nQUIT".
     * Expected: Returns pattern "quit" with ReplyType.END.
     */
    @Test
    fun `custom last line exact pattern matches on its own last line`() {
        val patterns = listOf(pattern("quit", ReplyType.END, MatchMode.LAST_LINE_EXACT))

        val result = detector.detect("Enough already\nQUIT", patterns)

        assertEquals("quit", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
    }

    /**
     * Tests that the same pattern text behaves differently when configured with different match modes.
     *
     * Preconditions: Pattern "halt" tested under ANYWHERE vs LAST_LINE_EXACT against "Please halt these messages".
     * Expected: ANYWHERE matches, while LAST_LINE_EXACT returns null.
     */
    @Test
    fun `same pattern text behaves differently per match mode`() {
        // Proves the mode is read from the entity rather than inferred from the pattern string.
        val anywhere = listOf(pattern("halt", ReplyType.STOP, MatchMode.ANYWHERE))
        val lastLine = listOf(pattern("halt", ReplyType.STOP, MatchMode.LAST_LINE_EXACT))
        val body = "Please halt these messages"

        assertNotNull("ANYWHERE should match mid-message", detector.detect(body, anywhere))
        assertNull("LAST_LINE_EXACT should not match mid-message", detector.detect(body, lastLine))
    }

    /**
     * Tests that configuring bare "stop" with ANYWHERE mode allows it to match mid-message.
     *
     * Preconditions: Pattern "stop" with ANYWHERE mode against "Hello, reply STOP to unsubscribe".
     * Expected: [OptOutDetector.detect] returns a non-null match.
     */
    @Test
    fun `bare stop as a custom anywhere pattern does match mid message`() {
        // The detector must not hardcode "stop" as last-line-only; if the user configures it as
        // ANYWHERE, it matches anywhere.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.ANYWHERE))

        assertNotNull(detector.detect("Hello, reply STOP to unsubscribe", patterns))
    }

    /**
     * Tests that when multiple patterns match a message, the first pattern in the list order is selected.
     *
     * Preconditions: Patterns ["end2end", "stop2stop"] tested against "stop2stop and end2end both appear".
     * Expected: Returns pattern "end2end" with ReplyType.END.
     */
    @Test
    fun `first matching pattern in list order wins`() {
        val patterns = listOf(
            pattern("end2end", ReplyType.END, MatchMode.ANYWHERE),
            pattern("stop2stop", ReplyType.STOP, MatchMode.ANYWHERE),
        )

        val result = detector.detect("stop2stop and end2end both appear", patterns)

        assertEquals("end2end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
    }
}
