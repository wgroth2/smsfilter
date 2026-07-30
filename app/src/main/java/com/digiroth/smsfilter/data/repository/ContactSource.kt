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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers whether a sender is a known local contact.
 *
 * Exists so the SMS pipeline depends on an abstraction rather than on [ContactRepository], which
 * requires an Android `Context` and a `ContentResolver` and therefore cannot be constructed in a
 * JVM unit test.
 */
fun interface ContactSource {

    /**
     * @param lookupValue The number to look up; the E.164 form when available, otherwise the raw
     *   originating address.
     * @return Whether the sender matches a saved contact, or [ContactLookupOutcome.Failed] if the
     *   lookup could not be completed.
     */
    suspend fun isKnownContact(lookupValue: String): ContactLookupOutcome
}

/**
 * The production [ContactSource], delegating to the Google Contacts repository.
 *
 * A thin adapter, so [ContactRepository] remains usable directly as its concrete type — the
 * Settings screen needs its permission-state and contact-count methods, which are outside this
 * narrower interface.
 */
@Singleton
class GoogleContactSource @Inject constructor(
    private val contactRepository: ContactRepository,
) : ContactSource {

    override suspend fun isKnownContact(lookupValue: String): ContactLookupOutcome =
        contactRepository.isKnownContact(lookupValue)
}
