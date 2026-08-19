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

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe in-memory deduplicator that suppresses processing of identical messages
 * arriving within a short time window.
 *
 * Useful for incoming RCS notifications and SMS broadcasts that may deliver the same
 * payload multiple times in rapid succession.
 *
 * @param timeProvider Clock source providing epoch millisecond timestamps.
 */
@Singleton
class MessageDeduplicator @Inject constructor(
    private val timeProvider: TimeProvider,
) {

    private val lock: Any = Any()
    private val recentMessages: MutableMap<String, Long> = mutableMapOf()

    /**
     * Checks whether a message with the given sender and body was already recorded within
     * the deduplication TTL window.
     *
     * @param senderAddress Originating sender address.
     * @param messageBody Content of the incoming message.
     * @return `true` if an identical message arrived within the TTL window, `false` otherwise.
     */
    fun isDuplicate(senderAddress: String, messageBody: String): Boolean = synchronized(lock) {
        val now = timeProvider.nowMillis()
        pruneExpired(now)
        val hash = hashMessage(senderAddress, messageBody)
        val timestamp = recentMessages[hash] ?: return false
        return (now - timestamp) < TTL_MILLIS
    }

    /**
     * Records arrival of a message for subsequent deduplication checks.
     *
     * @param senderAddress Originating sender address.
     * @param messageBody Content of the incoming message.
     */
    fun record(senderAddress: String, messageBody: String): Unit = synchronized(lock) {
        val now = timeProvider.nowMillis()
        pruneExpired(now)
        val hash = hashMessage(senderAddress, messageBody)
        recentMessages[hash] = now
    }

    /**
     * Clears all recorded message entries from memory.
     */
    fun clear(): Unit = synchronized(lock) {
        recentMessages.clear()
    }

    private fun pruneExpired(now: Long) {
        val cutoff = now - TTL_MILLIS
        recentMessages.entries.removeIf { it.value < cutoff }
    }

    private fun hashMessage(senderAddress: String, messageBody: String): String {
        val payload = "$senderAddress\n$messageBody"
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        /** Hash algorithm for message hashing; present on all Android/JVM runtimes. */
        private const val DIGEST_ALGORITHM: String = "SHA-256"

        /** Time-to-live for recorded message deduplication in milliseconds. */
        const val TTL_MILLIS: Long = 15_000L
    }
}
