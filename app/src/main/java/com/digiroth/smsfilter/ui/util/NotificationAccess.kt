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

package com.digiroth.smsfilter.ui.util

import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Whether Notification Access (a Special App Access) is granted to this app.
 *
 * This is the gate on MMS and RCS filtering: a non-default SMS app cannot see either without it,
 * so the Status dashboard, the Settings screen and the onboarding wizard all need to ask.
 *
 * Two sources are consulted because neither is reliable alone. [NotificationManagerCompat] is the
 * supported API, but on some OEM builds it omits listeners the system has in fact enabled, so a
 * miss falls back to reading the raw `enabled_notification_listeners` secure setting. A `null`
 * content resolver — which happens under unit tests using a bare context — reads as not granted,
 * the safe direction: the app understates its own capability rather than claiming coverage it
 * does not have.
 *
 * @param context Context used to query enabled notification listeners.
 * @return `true` if Notification Access is active for this package.
 */
fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
    if (enabledPackages.contains(context.packageName)) return true
    val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    return raw.contains(context.packageName)
}
