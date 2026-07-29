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

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the one-way hash used to recognise a repeat sender without storing their address.
 *
 * The auto-reply cooldown table is keyed by this hash, which is what lets the app enforce
 * "at most one reply per sender per 24 hours" while honouring the project-wide rule that no
 * phone number is ever persisted. SHA-256 is one-way, so a stored key cannot be reversed into
 * a number.
 *
 * `java.security.MessageDigest` is plain JVM, available in unit tests, so no seam is needed.
 */
@Singleton
class SenderHasher @Inject constructor() {

    /**
     * Hashes a sender address.
     *
     * The **raw** address must be passed, not an E.164-normalized form: replies are addressed
     * to the raw value, so hashing anything else would let the same sender bypass its own
     * cooldown whenever normalization changed the string.
     *
     * @param rawAddress The originating address exactly as received.
     * @return A 64-character lowercase hexadecimal SHA-256 digest.
     */
    fun hash(rawAddress: String): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
            .digest(rawAddress.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        /** Digest algorithm; guaranteed present on every Android and JVM runtime. */
        const val ALGORITHM = "SHA-256"
    }
}
