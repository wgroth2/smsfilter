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
 *
 * This is a plain `interface` rather than a `fun interface` only because it needs a companion
 * object for [UNKNOWN_SUBSCRIPTION_ID]; the seam and its rationale are otherwise unchanged.
 */
interface SmsSender {

    /**
     * Sends a text message.
     *
     * @param destinationAddress The address to reply to. Must be the raw originating address —
     *   a short code has to receive its reply at the exact address it sent from.
     * @param body The message body; for this app always a single opt-out keyword.
     * @param subscriptionId The SIM subscription the original message arrived on, so the reply
     *   leaves from the same MSISDN. Pass [UNKNOWN_SUBSCRIPTION_ID] when it could not be
     *   determined, which selects the platform's default SMS subscription.
     * @return `true` if the message was handed to the platform without error.
     */
    fun sendTextMessage(
        destinationAddress: String,
        body: String,
        subscriptionId: Int,
    ): Boolean

    /**
     * Constants shared by every [SmsSender] implementation and by the callers that feed it.
     */
    companion object {
        /**
         * Sentinel meaning "the receiving subscription could not be determined", which makes the
         * sender fall back to the platform's default SMS subscription.
         *
         * Numerically equal to `SubscriptionManager.INVALID_SUBSCRIPTION_ID` (-1), but declared
         * here independently so `SmsProcessingPipeline` can thread a subscription id through
         * without acquiring an `android.*` import and losing its JVM testability. Any value below
         * zero is treated as unknown; valid subscription ids are non-negative.
         */
        const val UNKNOWN_SUBSCRIPTION_ID: Int = -1
    }
}

/**
 * The production [SmsSender].
 *
 * `SmsManager.getDefault()` is deprecated from API 31, where the instance must come from the
 * system service registry instead; both paths are kept so the app works across API 26–35
 * without deprecation warnings on modern devices.
 *
 * On a dual-SIM device the reply must leave from the SIM that received the message: an aggregator
 * matches a STOP request against the originating MSISDN, so a reply sent from the other SIM
 * unsubscribes nothing. Hence every send resolves an `SmsManager` bound to the supplied
 * subscription whenever one is known.
 */
@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsSender {

    override fun sendTextMessage(
        destinationAddress: String,
        body: String,
        subscriptionId: Int,
    ): Boolean =
        runCatching {
            val manager = resolveSmsManager(subscriptionId)
            if (manager == null) {
                Log.e(TAG, "SmsManager unavailable; reply not sent")
                return@runCatching false
            }
            // A subscription id is not PII, and it is the only way to tell from a bug report
            // whether a reply went out the wrong SIM.
            Log.d(TAG, "Sending reply on subscription id $subscriptionId")
            manager.sendTextMessage(destinationAddress, null, body, null, null)
            true
        }.onFailure { error ->
            // Never rethrow: a send failure must be logged as a skipped reply, not crash the
            // worker and trigger a WorkManager retry that could double-send.
            Log.e(TAG, "Failed to send reply", error)
        }.getOrDefault(false)

    /**
     * Resolves the [SmsManager] to send with.
     *
     * @param subscriptionId The receiving subscription, or a negative value when unknown.
     * @return A manager bound to [subscriptionId] when it is valid, otherwise one bound to the
     *   default SMS subscription — the behaviour every single-SIM device has always had.
     */
    @Suppress("DEPRECATION")
    private fun resolveSmsManager(subscriptionId: Int): SmsManager? {
        val default: SmsManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

        if (subscriptionId < 0) return default

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            default?.createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    private companion object {
        /** Logcat tag for this class. */
        const val TAG = "AndroidSmsSender"
    }
}
