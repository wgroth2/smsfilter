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
        pattern("stop2stop", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("end2end", ReplyType.END, MatchMode.ANYWHERE),
        pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_EXACT),
        pattern("end", ReplyType.END, MatchMode.LAST_LINE_EXACT),
        pattern("stop to cancel", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to opt-out", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to opt out", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to end", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop to quit", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("stop=end", ReplyType.STOP, MatchMode.ANYWHERE),
    )

    // ---------------------------------------------------------------------
    // ANYWHERE matching
    // ---------------------------------------------------------------------

    @Test
    fun `detects stop2stop mid message`() {
        val result = detector.detect("stop2stop this deal", defaultPatterns)

        assertEquals("stop2stop", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects end2end mid message`() {
        val result = detector.detect("end2end encryption rocks", defaultPatterns)

        assertEquals("end2end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
        assertEquals("end", result?.replyKeyword)
    }

    @Test
    fun `detects anywhere pattern regardless of case`() {
        assertNotNull(detector.detect("STOP2STOP THIS DEAL", defaultPatterns))
        assertNotNull(detector.detect("Stop2Stop this deal", defaultPatterns))
    }

    @Test
    fun `detects anywhere pattern on a subject style first line`() {
        val body = "end2end\nSee the attached details"

        assertEquals("end2end", detector.detect(body, defaultPatterns)?.pattern)
    }

    @Test
    fun `detects stop to cancel mid message`() {
        val result = detector.detect("Flash sale! Text STOP to Cancel alerts", defaultPatterns)

        assertEquals("stop to cancel", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects stop to opt-out mid message`() {
        val result = detector.detect("Weekly specials: Reply STOP to opt-out anytime", defaultPatterns)

        assertEquals("stop to opt-out", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects stop to opt out mid message`() {
        val result = detector.detect("Promo info. Reply STOP to opt out", defaultPatterns)

        assertEquals("stop to opt out", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects stop to end mid message`() {
        val result = detector.detect("STOP to end account texts", defaultPatterns)

        assertEquals("stop to end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects stop to quit mid message`() {
        val result = detector.detect("Updates: text STOP to quit promotions", defaultPatterns)

        assertEquals("stop to quit", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    @Test
    fun `detects stop=end mid message`() {
        val result = detector.detect("Latest discounts. Text STOP=END to unsubscribe", defaultPatterns)

        assertEquals("stop=end", result?.pattern)
        assertEquals(ReplyType.STOP, result?.replyType)
        assertEquals("stop", result?.replyKeyword)
    }

    // ---------------------------------------------------------------------
    // LAST_LINE_CONTAINS matching
    //
    // Bodies here are taken verbatim from real messages captured on a test device, because the
    // point of this mode is to handle wording that actually occurs rather than wording that is
    // convenient to match.
    // ---------------------------------------------------------------------

    @Test
    fun `matches the reply stop to unsubscribe footer that last-line-exact misses`() {
        val body = "San Jose Clin Trials: Join a research study. Text \"YES\" to learn more " +
            "or contact us at 408-443-3542 Reply YES to learn more, STOP to unsubscribe"
        val contains = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull("the seeded exact rule cannot see this", detector.detect(body, defaultPatterns))
        assertEquals("stop", detector.detect(body, contains)?.pattern)
    }

    @Test
    fun `matches a keyword followed immediately by punctuation`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals("stop", detector.detect("Deal\nReply HELP for help, STOP to cancel.", patterns)?.pattern)
    }

    @Test
    fun `does not match end inside weekend`() {
        // The single most important negative case. A plain substring test would reply "end" to a
        // cheerful sign-off, which is exactly the failure this mode has to avoid being.
        val patterns = listOf(pattern("end", ReplyType.END, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Your order shipped\nHave a great weekend!", patterns))
    }

    @Test
    fun `does not match stop inside a domain name`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Sale today\nVisit stopandshop.com", patterns))
    }

    @Test
    fun `does not match a keyword run together with surrounding words`() {
        // Real AARP footer. Documented as out of scope for this mode: no boundary either side.
        val body = "AARP Advocates: Keep strengthening Medicare price negotiations. " +
            "Take a look: aarp.info/RxReport ReplySTOPtoCancel"
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect(body, patterns))
    }

    @Test
    fun `finds a later whole-word occurrence when an earlier one is embedded`() {
        // Scanning must not give up on the first hit: "stopandshop" comes first and is not a word.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals(
            "stop",
            detector.detect("Offer\nstopandshop.com deals - reply STOP to end", patterns)?.pattern,
        )
    }

    @Test
    fun `only searches the last line`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Reply STOP to unsubscribe\nThanks for shopping", patterns))
    }

    @Test
    fun `does not match a digit-adjacent keyword`() {
        // stop2stop is a separate ANYWHERE pattern and must not be claimed by this mode.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Deal\nreply stop2stop now", patterns))
    }

    @Test
    fun `still matches when the keyword is the entire last line`() {
        // The mode must be a strict superset of LAST_LINE_EXACT, or switching a pattern over to it
        // would lose detections that used to work.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertEquals("stop", detector.detect("Hello\nSTOP", patterns)?.pattern)
    }

    @Test
    fun `reports the contains mode on the result`() {
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))
        val result = detector.detect("Hi\nreply STOP to quit", patterns)

        assertEquals(MatchMode.LAST_LINE_CONTAINS, result?.matchMode)
        assertEquals(ReplyType.STOP, result?.replyType)
    }

    @Test
    fun `blank contains pattern never matches`() {
        val patterns = listOf(pattern("   ", ReplyType.STOP, MatchMode.LAST_LINE_CONTAINS))

        assertNull(detector.detect("Hello\nreply STOP to unsubscribe", patterns))
    }

    // ---------------------------------------------------------------------
    // LAST_LINE_EXACT matching
    // ---------------------------------------------------------------------

    @Test
    fun `detects bare stop alone on the last line`() {
        val result = detector.detect("Hello\nSTOP", defaultPatterns)

        assertEquals("stop", result?.pattern)
        assertEquals(MatchMode.LAST_LINE_EXACT, result?.matchMode)
        assertEquals(ReplyType.STOP, result?.replyType)
    }

    @Test
    fun `detects bare end alone on the last line regardless of case`() {
        val result = detector.detect("Thanks\nEnd", defaultPatterns)

        assertEquals("end", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
    }

    @Test
    fun `detects lowercase stop alone on the last line`() {
        assertEquals("stop", detector.detect("Thanks\nstop", defaultPatterns)?.pattern)
    }

    @Test
    fun `ignores stop embedded in marketing copy on a single line`() {
        // The whole reason bare keywords are last-line-exact: this phrasing appears in a large
        // share of legitimate marketing SMS and must never trigger a reply.
        assertNull(detector.detect("Hello, reply STOP to unsubscribe", defaultPatterns))
    }

    @Test
    fun `ignores stop embedded in a word on a non final line`() {
        assertNull(detector.detect("Postop care instructions\nCall us", defaultPatterns))
    }

    @Test
    fun `ignores stop embedded in a word even on the last line`() {
        // "Postop" is not exactly "stop", so LAST_LINE_EXACT must not fire.
        assertNull(detector.detect("Care instructions\nPostop", defaultPatterns))
    }

    @Test
    fun `ignores last line that merely contains stop among other words`() {
        assertNull(detector.detect("Hello\nPlease stop messaging me", defaultPatterns))
    }

    @Test
    fun `detects stop on the last line with surrounding spaces`() {
        assertEquals("stop", detector.detect("Hello\n  STOP  ", defaultPatterns)?.pattern)
    }

    @Test
    fun `detects stop on the last non blank line when trailing blank lines follow`() {
        val body = "Hello\nSTOP\n\n   \n"

        assertEquals("stop", detector.detect(body, defaultPatterns)?.pattern)
    }

    @Test
    fun `detects stop when padded with unicode space separators`() {
        // U+00A0 (non-breaking space) and U+2003 (em space) both occur in real marketing SMS.
        // Character.isWhitespace returns false for U+00A0, so relying on trim() alone would miss
        // this and silently drop a legitimate opt-out.
        val body = "Hello\n STOP "

        assertEquals("stop", detector.detect(body, defaultPatterns)?.pattern)
        assertEquals("stop", detector.detect("Hello\n stop ", defaultPatterns)?.pattern)
    }

    @Test
    fun `treats a unicode space only trailing line as blank`() {
        val body = "Hello\nSTOP\n "

        assertEquals(
            "a non-breaking-space line must not become the last line",
            "stop",
            detector.detect(body, defaultPatterns)?.pattern,
        )
    }

    @Test
    fun `handles carriage return line endings`() {
        assertEquals("stop", detector.detect("Hello\r\nSTOP", defaultPatterns)?.pattern)
    }

    @Test
    fun `detects stop in a single line message that is only the keyword`() {
        assertEquals("stop", detector.detect("STOP", defaultPatterns)?.pattern)
    }

    // ---------------------------------------------------------------------
    // Degenerate input
    // ---------------------------------------------------------------------

    @Test
    fun `empty message never matches`() {
        assertNull(detector.detect("", defaultPatterns))
    }

    @Test
    fun `whitespace only message never matches`() {
        assertNull(detector.detect("   \n\n  ", defaultPatterns))
    }

    @Test
    fun `empty pattern list never matches`() {
        assertNull(detector.detect("Hello\nSTOP", emptyList()))
    }

    @Test
    fun `blank pattern is ignored rather than matching every message`() {
        val patterns = listOf(pattern("", ReplyType.STOP, MatchMode.ANYWHERE))

        assertNull(detector.detect("Any message at all", patterns))
    }

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

    @Test
    fun `custom anywhere pattern matches mid message`() {
        val patterns = listOf(pattern("unsubscribe", ReplyType.STOP, MatchMode.ANYWHERE))

        val result = detector.detect("Click to unsubscribe from our list", patterns)

        assertEquals("unsubscribe", result?.pattern)
        assertEquals(MatchMode.ANYWHERE, result?.matchMode)
    }

    @Test
    fun `custom last line exact pattern does not match mid message`() {
        val patterns = listOf(pattern("quit", ReplyType.END, MatchMode.LAST_LINE_EXACT))

        assertNull(detector.detect("Please quit sending these", patterns))
    }

    @Test
    fun `custom last line exact pattern matches on its own last line`() {
        val patterns = listOf(pattern("quit", ReplyType.END, MatchMode.LAST_LINE_EXACT))

        val result = detector.detect("Enough already\nQUIT", patterns)

        assertEquals("quit", result?.pattern)
        assertEquals(ReplyType.END, result?.replyType)
    }

    @Test
    fun `same pattern text behaves differently per match mode`() {
        // Proves the mode is read from the entity rather than inferred from the pattern string.
        val anywhere = listOf(pattern("halt", ReplyType.STOP, MatchMode.ANYWHERE))
        val lastLine = listOf(pattern("halt", ReplyType.STOP, MatchMode.LAST_LINE_EXACT))
        val body = "Please halt these messages"

        assertNotNull("ANYWHERE should match mid-message", detector.detect(body, anywhere))
        assertNull("LAST_LINE_EXACT should not match mid-message", detector.detect(body, lastLine))
    }

    @Test
    fun `bare stop as a custom anywhere pattern does match mid message`() {
        // The detector must not hardcode "stop" as last-line-only; if the user configures it as
        // ANYWHERE, it matches anywhere.
        val patterns = listOf(pattern("stop", ReplyType.STOP, MatchMode.ANYWHERE))

        assertNotNull(detector.detect("Hello, reply STOP to unsubscribe", patterns))
    }

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
