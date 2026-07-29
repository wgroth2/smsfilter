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

package com.digiroth.smsfilter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [SenderHasher]. */
class SenderHasherTest {

    private val hasher = SenderHasher()

    @Test
    fun `produces 64 lowercase hex characters`() {
        val hash = hasher.hash("+16505551234")

        assertEquals("SHA-256 hex is always 64 characters", 64, hash.length)
        assertTrue("must be lowercase hex only", hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `is stable across calls`() {
        // The cooldown table is keyed by this value, so an unstable hash would let a sender bypass
        // its own cooldown on every message.
        assertEquals(hasher.hash("+16505551234"), hasher.hash("+16505551234"))
    }

    @Test
    fun `is stable across instances`() {
        assertEquals(SenderHasher().hash("89887"), SenderHasher().hash("89887"))
    }

    @Test
    fun `different addresses produce different hashes`() {
        assertNotEquals(hasher.hash("+16505551234"), hasher.hash("+16505551235"))
    }

    @Test
    fun `raw and normalized forms of one number hash differently`() {
        // Documents why the RAW address must be hashed consistently: the two forms are not
        // interchangeable, so hashing a different form than the one replied to would break the gate.
        assertNotEquals(hasher.hash("(650) 555-1234"), hasher.hash("+16505551234"))
    }

    @Test
    fun `matches the known SHA-256 digest of a short code`() {
        // Pins the algorithm and encoding against an externally computed digest
        // (printf '89887' | shasum -a 256). If this fails, the hash is no longer SHA-256 over
        // UTF-8 bytes, and every previously stored cooldown key has silently become unmatchable.
        assertEquals(
            "1a6b881b693527081fdf188d8d506819b4e78be64f7b99a8b4ddddc97162cc41",
            hasher.hash("89887"),
        )
    }

    @Test
    fun `handles alphanumeric and empty addresses without throwing`() {
        assertEquals(64, hasher.hash("VERIZON").length)
        assertEquals(64, hasher.hash("").length)
    }

    @Test
    fun `is case sensitive`() {
        assertNotEquals(hasher.hash("verizon"), hasher.hash("VERIZON"))
    }
}
