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

package com.digiroth.smsfilter.platform

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends an outgoing SMS.
 *
 * This is the most consequential seam in the app. The three auto-reply safety gates — dry-run
 * mode, unrepliable sender, and the 24-hour cooldown — are all defined by what must *not*
 * happen, and a negative like "no message was sent" cannot be asserted against a static
 * `SmsManager` call. Routing every send through this interface is what makes those gates
 * verifiable rather than merely intended.
 */
fun interface SmsSender {

    /**
     * Sends a text message.
     *
     * @param destinationAddress The address to reply to. Must be the raw originating address —
     *   a short code has to receive its reply at the exact address it sent from.
     * @param body The message body; for this app always a single opt-out keyword.
     * @return `true` if the message was handed to the platform without error.
     */
    fun sendTextMessage(destinationAddress: String, body: String): Boolean
}

/**
 * The production [SmsSender].
 *
 * `SmsManager.getDefault()` is deprecated from API 31, where the instance must come from the
 * system service registry instead; both paths are kept so the app works across API 26–35
 * without deprecation warnings on modern devices.
 */
@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSender {

    override fun sendTextMessage(destinationAddress: String, body: String): Boolean =
        runCatching {
            val manager = resolveSmsManager()
            if (manager == null) {
                Log.e(TAG, "SmsManager unavailable; reply not sent")
                return@runCatching false
            }
            manager.sendTextMessage(destinationAddress, null, body, null, null)
            true
        }.onFailure { error ->
            // Never rethrow: a send failure must be logged as a skipped reply, not crash the
            // worker and trigger a WorkManager retry that could double-send.
            Log.e(TAG, "Failed to send reply", error)
        }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun resolveSmsManager(): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private companion object {
        const val TAG = "AndroidSmsSender"
    }
}
