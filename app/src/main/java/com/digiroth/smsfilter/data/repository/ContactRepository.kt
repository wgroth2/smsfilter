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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks incoming senders against the device's synced Google Contacts.
 *
 * For official Android documentation on `ContactsContract.PhoneLookup`, see:
 * - PhoneLookup Content Provider: [https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup](https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup)
 *
 * Uses `ContactsContract.PhoneLookup`, which performs the platform's own number matching — that
 * is what allows a contact stored as "(650) 555-1234" to match an incoming "+16505551234"
 * without the app doing its own normalization. The query is local, so no network call and no
 * Google Sign-In or OAuth is involved.
 *
 * No contact data is retained: the query asks only whether a row exists.
 */
@Singleton
class ContactRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Whether the sender matches a saved contact.
     *
     * **Permission guard.** `READ_CONTACTS` is checked before every query, and a missing
     * permission returns [ContactLookupOutcome.NotFound] rather than throwing. This path is
     * reachable in ordinary use: onboarding lets the user finish the wizard with contacts denied,
     * so an unguarded query would raise `SecurityException` inside the worker on the very first
     * message. Treating the sender as unknown is the specified behaviour — detection continues.
     *
     * @param lookupValue The number to look up; the E.164 form when available, otherwise the raw
     *   originating address.
     * @return [ContactLookupOutcome.Found] if a contact matches, [ContactLookupOutcome.NotFound]
     *   if none does or the permission is missing, or [ContactLookupOutcome.Failed] if the query
     *   itself errored.
     */
    suspend fun isKnownContact(lookupValue: String): ContactLookupOutcome =
        withContext(Dispatchers.IO) {
            if (lookupValue.isBlank()) return@withContext ContactLookupOutcome.NotFound

            if (!hasReadContactsPermission()) {
                Log.w(TAG, "READ_CONTACTS not granted; treating sender as unknown")
                return@withContext ContactLookupOutcome.NotFound
            }

            runCatching {
                val uri: Uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(lookupValue),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup._ID),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) ContactLookupOutcome.Found else ContactLookupOutcome.NotFound
                } ?: ContactLookupOutcome.NotFound
            }.getOrElse { error ->
                Log.e(TAG, "Contacts lookup failed", error)
                ContactLookupOutcome.Failed(reason = error.javaClass.simpleName)
            }
        }

    /**
     * Counts contacts holding at least one phone number. Backs the Settings screen's
     * "Google Contacts: Accessible (X contacts found)" diagnostic.
     *
     * @return The number of contacts with a phone number, or `null` if the permission is missing
     *   or the query failed.
     */
    suspend fun countContactsWithPhoneNumbers(): Int? = withContext(Dispatchers.IO) {
        if (!hasReadContactsPermission()) return@withContext null

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.count }
        }.getOrElse { error ->
            Log.e(TAG, "Contacts count failed", error)
            null
        }
    }

    /**
     * @return `true` if `READ_CONTACTS` is currently granted.
     */
    fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "ContactRepository"
    }
}
