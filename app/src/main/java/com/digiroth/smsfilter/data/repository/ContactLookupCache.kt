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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small in-memory cache of senders already verified as known contacts.
 *
 * Its purpose is latency: a cache hit lets the pipeline skip the remote HubSpot search entirely
 * for a sender it has already resolved. Entries expire after [TTL_MILLIS] so a contact deleted
 * upstream stops being treated as known within a bounded window.
 *
 * **Only positive results are cached.** Caching a miss would mean a contact the user adds right
 * after an unknown-sender message keeps being treated as unknown for the rest of the window —
 * and the consequence of that mistake is auto-replying to a real contact. A redundant lookup is
 * the cheaper error.
 *
 * `android.util.LruCache` is deliberately not used: it is a framework class that cannot run in a
 * JVM unit test, and it has no expiry, which is the property this cache exists for. Access order
 * is maintained here so eviction removes the least recently used entry once [MAX_ENTRIES] is
 * reached.
 *
 * This cache is never written to disk — it holds sender addresses, which the app does not persist.
 *
 * Access is synchronized because the SMS pipeline can run concurrently for messages arriving
 * close together.
 *
 * @property timeProvider Supplies the clock used for expiry, so tests control it directly.
 */
@Singleton
class ContactLookupCache @Inject constructor(
    private val timeProvider: TimeProvider,
) {

    /** Access-ordered so the eldest entry is the least recently used. */
    private val entries = object : LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Whether this sender is known to be a contact, according to a still-valid cache entry.
     *
     * An expired entry is removed as a side effect, so the cache cannot accumulate stale keys for
     * senders that never recur.
     *
     * @param lookupKey The value the sender was resolved by — normally the E.164 form, or the raw
     *   address when normalization was skipped or failed.
     * @return `true` if a live entry marks this sender as known; `false` if there is no live
     *   entry. Never distinguishes "cached as unknown", because misses are not cached.
     */
    fun isKnownContact(lookupKey: String): Boolean = synchronized(entries) {
        val cachedAt = entries[lookupKey] ?: return false
        val isLive = timeProvider.nowMillis() - cachedAt < TTL_MILLIS
        if (!isLive) entries.remove(lookupKey)
        isLive
    }

    /**
     * Records that a sender was verified as a known contact.
     *
     * @param lookupKey The value the sender was resolved by.
     */
    fun markKnownContact(lookupKey: String) = synchronized(entries) {
        entries[lookupKey] = timeProvider.nowMillis()
        Unit
    }

    /** Drops every entry. Used when contact permissions change or the user disconnects HubSpot. */
    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * @return The number of entries currently held, expired or not. Exposed for tests and
     *   diagnostics only.
     */
    fun size(): Int = synchronized(entries) { entries.size }

    companion object {
        /** How long a verified result stays usable, per the specification's 15-minute window. */
        const val TTL_MILLIS: Long = 15L * 60L * 1000L

        /** Upper bound on retained entries, so a burst of distinct senders cannot grow the map. */
        const val MAX_ENTRIES: Int = 128

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
