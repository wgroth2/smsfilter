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

package com.digiroth.smsfilter.ui.permissions

import android.Manifest
import android.os.Build

/**
 * The runtime permissions this app requests, and which of them block onboarding.
 *
 * `POST_NOTIFICATIONS` did not exist before API 33. Including it in a request array on an older
 * device asks for a permission the platform does not recognise, so the list is built conditionally
 * and the permission is treated as already satisfied below API 33.
 */
object AppPermissions {

    /** Required to observe incoming messages at all. */
    const val RECEIVE_SMS: String = Manifest.permission.RECEIVE_SMS

    /** Required to send the opt-out reply. */
    const val SEND_SMS: String = Manifest.permission.SEND_SMS

    /** Optional: without it every sender is treated as unknown. */
    const val READ_CONTACTS: String = Manifest.permission.READ_CONTACTS

    /** Required to receive MMS notifications and broadcasts. */
    const val RECEIVE_MMS: String = Manifest.permission.RECEIVE_MMS

    /** Required to read messages from the telephony content provider for MMS text resolution. */
    const val READ_SMS: String = Manifest.permission.READ_SMS

    /** Required from API 33 to surface detections. */
    const val POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"

    /**
     * Permissions that must be granted before onboarding can continue.
     *
     * @return The blocking permissions applicable to this device's API level.
     */
    fun blocking(): List<String> = buildList {
        add(RECEIVE_SMS)
        add(SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(POST_NOTIFICATIONS)
        }
    }

    /**
     * Permissions the app asks for but does not require. A denial here shows a warning and the user
     * may still finish the wizard — contacts access is explicitly non-blocking by design.
     *
     * @return The list of optional permissions.
     */
    fun optional(): List<String> = listOf(READ_CONTACTS)

    /** @return Every permission to include in a request, blocking and optional together. */
    fun all(): List<String> = blocking() + optional()

    /**
     * Whether a permission is inapplicable on this device and should be treated as satisfied.
     *
     * @param permission The permission to check.
     * @return `true` if the platform predates the permission's introduction.
     */
    fun isNotApplicable(permission: String): Boolean =
        permission == POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}
