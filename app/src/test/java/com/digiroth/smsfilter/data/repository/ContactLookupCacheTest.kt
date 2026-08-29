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

package com.digiroth.smsfilter.data.repository

import com.digiroth.smsfilter.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ContactLookupCache].
 *
 * Verifies caching behavior, TTL expiration boundaries, LRU capacity limits,
 * and cache clearing using a controllable fake time provider.
 */
class ContactLookupCacheTest {

    private class FakeTimeProvider(var now: Long = 0L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    private val clock = FakeTimeProvider()
    private val cache = ContactLookupCache(clock)

    /**
     * Tests that querying a phone number or identifier that has not been cached returns false.
     *
     * Preconditions: Cache is freshly initialized and empty.
     * Expected: [ContactLookupCache.isKnownContact] returns false.
     */
    @Test
    fun `unknown key is not cached`() {
        assertFalse(cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that marking a contact as known allows subsequent lookup to return true.
     *
     * Preconditions: Phone number is recorded with [ContactLookupCache.markKnownContact].
     * Expected: [ContactLookupCache.isKnownContact] returns true for the recorded number.
     */
    @Test
    fun `marked key is reported as known`() {
        cache.markKnownContact("+16505551234")

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that a cached entry remains valid when checked immediately prior to TTL expiration.
     *
     * Preconditions: Phone number is cached at time 0, and clock advances to TTL - 1 ms.
     * Expected: [ContactLookupCache.isKnownContact] returns true.
     */
    @Test
    fun `entry survives just before the ttl expires`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS - 1

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that a cached entry is treated as stale exactly at the TTL expiration boundary.
     *
     * Preconditions: Phone number is cached at time 0, and clock advances to exactly TTL ms.
     * Expected: [ContactLookupCache.isKnownContact] returns false.
     */
    @Test
    fun `entry expires exactly at the ttl`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS

        assertFalse("an entry at exactly the TTL must be treated as stale", cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that reading an expired entry evicts it from the internal cache map.
     *
     * Preconditions: Phone number cached at time 0, clock advances to TTL + 1 ms, and lookup is performed.
     * Expected: Lookup returns false and cache size is reduced to 0.
     */
    @Test
    fun `expired entry is evicted on read`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS + 1

        cache.isKnownContact("+16505551234")

        assertEquals("a stale key must not linger", 0, cache.size())
    }

    /**
     * Tests that re-marking an existing key resets its TTL timestamp.
     *
     * Preconditions: Phone number cached at time 0, advanced to TTL - 1 ms, re-marked, then advanced another TTL - 1 ms.
     * Expected: Lookup still returns true because the TTL was refreshed.
     */
    @Test
    fun `re-marking refreshes the ttl`() {
        cache.markKnownContact("+16505551234")
        clock.now = ContactLookupCache.TTL_MILLIS - 1
        cache.markKnownContact("+16505551234")
        clock.now += ContactLookupCache.TTL_MILLIS - 1

        assertTrue(cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that caching one contact key does not affect lookup results for other keys.
     *
     * Preconditions: "+16505551234" is marked as known.
     * Expected: Lookup for distinct number "+16505559999" returns false.
     */
    @Test
    fun `keys are independent`() {
        cache.markKnownContact("+16505551234")

        assertFalse(cache.isKnownContact("+16505559999"))
    }

    /**
     * Tests that calling clear purges all entries from the cache.
     *
     * Preconditions: Multiple contact identifiers are marked as known in the cache.
     * Expected: Cache size becomes 0 and previously marked numbers return false.
     */
    @Test
    fun `clear removes every entry`() {
        cache.markKnownContact("+16505551234")
        cache.markKnownContact("89887")

        cache.clear()

        assertEquals(0, cache.size())
        assertFalse(cache.isKnownContact("+16505551234"))
    }

    /**
     * Tests that inserting more items than MAX_ENTRIES does not allow the cache to exceed its maximum capacity.
     *
     * Preconditions: MAX_ENTRIES + 50 distinct contact identifiers are inserted.
     * Expected: Cache size is capped at [ContactLookupCache.MAX_ENTRIES].
     */
    @Test
    fun `does not grow beyond the entry cap`() {
        repeat(ContactLookupCache.MAX_ENTRIES + 50) { index ->
            cache.markKnownContact("sender-$index")
        }

        assertEquals(ContactLookupCache.MAX_ENTRIES, cache.size())
    }

    /**
     * Tests that LRU eviction removes the least recently used entry when capacity is exceeded.
     *
     * Preconditions: "oldest" is inserted first, followed by filler entries to reach capacity - 1.
     * "oldest" is then accessed to update its access order, and a new entry is inserted.
     * Expected: "oldest" survives eviction and remains present alongside the new entry.
     */
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
