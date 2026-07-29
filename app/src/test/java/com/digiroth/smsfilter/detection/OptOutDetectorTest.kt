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

    /** The same four patterns the database seeds on first creation. */
    private val defaultPatterns: List<OptOutPatternEntity> = listOf(
        pattern("stop2stop", ReplyType.STOP, MatchMode.ANYWHERE),
        pattern("end2end", ReplyType.END, MatchMode.ANYWHERE),
        pattern("stop", ReplyType.STOP, MatchMode.LAST_LINE_EXACT),
        pattern("end", ReplyType.END, MatchMode.LAST_LINE_EXACT),
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
