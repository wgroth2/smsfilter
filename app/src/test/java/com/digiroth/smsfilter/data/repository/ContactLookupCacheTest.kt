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

package com.digiroth.smsfilter.data.repository

import com.digiroth.smsfilter.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ContactLookupCache].
 *
 * Time is driven by a mutable fake so expiry is asserted exactly at the boundary rather than by
 * sleeping.
 */
class ContactLookupCacheTest {

    private class FakeTimeProvider(var now: Long = 0L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    private val clock = FakeTimeProvider()
    private val cache = ContactLookupCache(clock)

    @Test
    fun `unknown key is not cached`() {
        assertFalse(cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `marked key is reported as known`() {
        cache.markKnownContact("+16505551234")

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `entry survives just before the ttl expires`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS - 1

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `entry expires exactly at the ttl`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS

        assertFalse("an entry at exactly the TTL must be treated as stale", cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `expired entry is evicted on read`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS + 1

        cache.isKnownContact("+16505551234")

        assertEquals("a stale key must not linger", 0, cache.size())
    }

    @Test
    fun `re-marking refreshes the ttl`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS - 1
        cache.markKnownContact("+16505551234")
        clock.now += ContactLookupCache.TTL_MILLIS - 1

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `keys are independent`() {
        cache.markKnownContact("+16505551234")

        assertFalse(cache.isKnownContact("+16505559999"))
    }

    @Test
    fun `clear removes every entry`() {
        cache.markKnownContact("+16505551234")
        cache.markKnownContact("89887")

        cache.clear()

        assertEquals(0, cache.size())
        assertFalse(cache.isKnownContact("+16505551234"))
    }

    @Test
    fun `does not grow beyond the entry cap`() {
        repeat(ContactLookupCache.MAX_ENTRIES + 50) { index ->
            cache.markKnownContact("sender-$index")
        }

        assertEquals(ContactLookupCache.MAX_ENTRIES, cache.size())
    }

    @Test
    fun `eviction discards the least recently used entry`() {
        cache.markKnownContact("oldest")
        repeat(ContactLookupCache.MAX_ENTRIES - 1) { index -> cache.markKnownContact("filler-$index") }

        // Touch "oldest" so it is no longer the least recently used, then overflow by one.
        assertTrue(cache.isKnownContact("oldest"))
        cache.markKnownContact("newcomer")

        assertTrue("a recently read entry must survive eviction", cache.isKnownContact("oldest"))
        assertTrue(cache.isKnownContact("newcomer"))
    }
}
