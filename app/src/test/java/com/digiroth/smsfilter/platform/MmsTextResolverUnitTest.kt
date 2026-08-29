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
import org.junit.Test

/**
 * Unit tests for [MmsTextResolver] snippet sanitization and matching logic.
 */
class MmsTextResolverUnitTest {

    private val resolver = MmsTextResolver(context = ContextWrapper(null))

    @Test
    fun sanitizeSnippet_stripsImageNewlinePrefix() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to unsubscribe")
        assertEquals("Reply STOP to unsubscribe", result)
    }

    @Test
    fun sanitizeSnippet_stripsImageCrLfPrefix() {
        val result = resolver.sanitizeSnippet("Image\r\nReply STOP to unsubscribe")
        assertEquals("Reply STOP to unsubscribe", result)
    }

    @Test
    fun sanitizeSnippet_stripsImageSpacePrefix() {
        val result = resolver.sanitizeSnippet("Image Reply STOP to cancel")
        assertEquals("Reply STOP to cancel", result)
    }

    @Test
    fun sanitizeSnippet_stripsPhotoPrefix() {
        val result = resolver.sanitizeSnippet("Photo\nSTOP to end")
        assertEquals("STOP to end", result)
    }

    @Test
    fun sanitizeSnippet_stripsTrailingEllipsis() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to opt-out...")
        assertEquals("Reply STOP to opt-out", result)
    }

    @Test
    fun sanitizeSnippet_stripsTrailingUnicodeEllipsis() {
        val result = resolver.sanitizeSnippet("Image\nReply STOP to opt-out…")
        assertEquals("Reply STOP to opt-out", result)
    }

    @Test
    fun sanitizeSnippet_handlesPlainMessageWithoutPrefix() {
        val result = resolver.sanitizeSnippet("STOP")
        assertEquals("STOP", result)
    }

    @Test
    fun sanitizeSnippet_returnsEmptyForOnlyImage() {
        val result = resolver.sanitizeSnippet("Image")
        assertEquals("", result)
    }

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
        org.junit.Assert.assertNull(result)
        assertEquals(3, failingResolver.callCount)
    }
}
