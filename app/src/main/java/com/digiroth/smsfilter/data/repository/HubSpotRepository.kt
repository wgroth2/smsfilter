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

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks incoming senders against HubSpot CRM contacts.
 *
 * Declared as an interface only in this phase; the Retrofit-backed implementation arrives with
 * the HubSpot API layer. Defining the contract now lets the SMS pipeline be written and tested
 * against it, and lets Hilt satisfy the dependency with [NoOpHubSpotRepository] in the meantime.
 */
interface HubSpotRepository {

    /**
     * Searches HubSpot for a contact matching the sender.
     *
     * Implementations should query HubSpot's normalized `hs_searchable_calculated_phone_number`
     * property using [e164Value] first, then retry with [rawDigits] if the first search returns
     * nothing — HubSpot stores raw `phone` values in whatever format they were entered, so an
     * exact match against E.164 alone misses real contacts.
     *
     * @param e164Value The sender's E.164 form, or `null` when normalization was skipped or failed.
     * @param rawDigits Every digit of the raw originating address, used as the fallback search.
     * @return [ContactLookupOutcome.Found] if a contact matches, [ContactLookupOutcome.NotFound]
     *   if none does, or [ContactLookupOutcome.Failed] on a network, rate-limit, or auth error.
     *   A failure must never be reported as [ContactLookupOutcome.NotFound]: the caller relies on
     *   the distinction to keep processing the message rather than assuming the sender is unknown
     *   for the wrong reason.
     */
    suspend fun isKnownContact(e164Value: String?, rawDigits: String): ContactLookupOutcome

    /**
     * Verifies that the stored token and network path work.
     *
     * @return [ContactLookupOutcome.Found] or [ContactLookupOutcome.NotFound] on a successful
     *   call — both mean the connection is healthy — or [ContactLookupOutcome.Failed] otherwise.
     */
    suspend fun testConnection(): ContactLookupOutcome
}

/**
 * Placeholder [HubSpotRepository] used until the real API layer exists.
 *
 * Returns [ContactLookupOutcome.NotFound] rather than [ContactLookupOutcome.Failed] so the
 * Google-Contacts-only happy path behaves exactly as it will in production when the user has not
 * connected HubSpot: the sender is simply unknown to HubSpot, which is true.
 *
 * This binding is replaced by the real implementation in the HubSpot API phase.
 */
@Singleton
class NoOpHubSpotRepository @Inject constructor() : HubSpotRepository {

    override suspend fun isKnownContact(e164Value: String?, rawDigits: String): ContactLookupOutcome {
        Log.d(TAG, "HubSpot implementation not yet present; reporting sender as not found")
        return ContactLookupOutcome.NotFound
    }

    override suspend fun testConnection(): ContactLookupOutcome =
        ContactLookupOutcome.Failed(reason = "HubSpot support not yet implemented")

    private companion object {
        const val TAG = "NoOpHubSpotRepository"
    }
}
