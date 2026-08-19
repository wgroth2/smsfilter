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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MessageDeduplicator] verifying deduplication TTL window, distinct message
 * handling, and cache expiration.
 */
class MessageDeduplicatorTest {

    private class TestTimeProvider(var now: Long = 1_000_000L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    private val timeProvider = TestTimeProvider()
    private val deduplicator = MessageDeduplicator(timeProvider)

    @Test
    fun unrecordedMessageIsNotDuplicate() {
        assertFalse(deduplicator.isDuplicate("+16505551234", "STOP"))
    }

    @Test
    fun recordedMessageWithinTtlIsDuplicate() {
        val sender = "+16505551234"
        val body = "STOP"

        deduplicator.record(sender, body)

        assertTrue(deduplicator.isDuplicate(sender, body))
    }

    @Test
    fun differentSenderIsNotDuplicate() {
        val body = "STOP"
        deduplicator.record("+16505551234", body)

        assertFalse(deduplicator.isDuplicate("+16505559999", body))
    }

    @Test
    fun differentBodyIsNotDuplicate() {
        val sender = "+16505551234"
        deduplicator.record(sender, "STOP")

        assertFalse(deduplicator.isDuplicate(sender, "END"))
    }

    @Test
    fun messageOutsideTtlExpires() {
        val sender = "+16505551234"
        val body = "STOP"

        deduplicator.record(sender, body)
        assertTrue(deduplicator.isDuplicate(sender, body))

        // Advance beyond the 15-second TTL
        timeProvider.now += MessageDeduplicator.TTL_MILLIS + 1L

        assertFalse(deduplicator.isDuplicate(sender, body))
    }

    @Test
    fun clearRemovesAllRecords() {
        val sender = "+16505551234"
        val body = "STOP"

        deduplicator.record(sender, body)
        assertTrue(deduplicator.isDuplicate(sender, body))

        deduplicator.clear()
        assertFalse(deduplicator.isDuplicate(sender, body))
    }
}
