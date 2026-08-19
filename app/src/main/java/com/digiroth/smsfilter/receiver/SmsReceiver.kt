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

package com.digiroth.smsfilter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.digiroth.smsfilter.platform.SmsSender
import com.digiroth.smsfilter.worker.SmsLookupWorker

/**
 * Receives incoming SMS broadcasts and hands each complete message to [SmsLookupWorker].
 *
 * Manifest-declared, so it runs even when the app has no process alive. It performs no lookups and
 * makes no decisions — `onReceive` has a few seconds at most before the system may kill the
 * process, so all real work is deferred to an expedited worker.
 *
 * No Hilt entry point is needed: this class only reads the intent and enqueues work.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Reconstruction must finish synchronously here, before onReceive returns.
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .onFailure { error -> Log.e(TAG, "Failed to extract SMS from broadcast", error) }
            .getOrNull()

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "SMS broadcast contained no messages")
            return
        }

        // Multi-part reconstruction. getMessagesFromIntent returns segments already ordered by
        // sequence number, and handles the 3gpp/3gpp2 format difference that varies by carrier and
        // OEM — which is why the raw `pdus` extra is never parsed by hand here.
        //
        // Concatenating before handoff is essential, not cosmetic: LAST_LINE_EXACT detection
        // depends on the true final line of the whole message, so running a single segment through
        // detection would produce false negatives on legitimate opt-outs.
        val sender = messages.first().originatingAddress
        if (sender.isNullOrBlank()) {
            Log.w(TAG, "SMS had no originating address; cannot process or reply")
            return
        }

        val body = messages.joinToString(separator = "") { segment ->
            val segmentBody = segment.messageBody
            if (segmentBody == null) {
                Log.w(TAG, "SMS segment contained null message body")
                ""
            } else {
                segmentBody
            }
        }
        val receivedAt = messages.first().timestampMillis

        // Captured here and carried all the way to the sender: on a dual-SIM device the opt-out
        // reply has to leave from the SIM that received the message, because the aggregator matches
        // a STOP request against the originating MSISDN.
        val subscriptionId = resolveSubscriptionId(intent)
        Log.d(TAG, "SMS received on subscription id $subscriptionId")

        enqueueLookup(context, sender, body, receivedAt, subscriptionId)
    }

    /**
     * Extracts the SIM subscription the broadcast arrived on.
     *
     * Both keys are consulted because OEMs disagree about which one they populate: AOSP puts the
     * value under [SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX], while a number of vendor builds
     * still only write the older [LEGACY_SUBSCRIPTION_EXTRA]. Reading just one of them silently
     * yields the unknown sentinel on the other half of the device population.
     *
     * @param intent The received SMS broadcast.
     * @return The receiving subscription id, or [SmsSender.UNKNOWN_SUBSCRIPTION_ID] when neither
     *   extra is present.
     */
    private fun resolveSubscriptionId(intent: Intent): Int {
        val modern = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SmsSender.UNKNOWN_SUBSCRIPTION_ID,
        )
        if (modern >= 0) return modern

        return intent.getIntExtra(LEGACY_SUBSCRIPTION_EXTRA, SmsSender.UNKNOWN_SUBSCRIPTION_ID)
    }

    /**
     * Enqueues the lookup worker as an expedited request.
     *
     * @param context Receiver context, used to reach the WorkManager singleton.
     * @param sender The raw originating address.
     * @param body The fully reconstructed message body.
     * @param receivedAt Arrival time in epoch milliseconds.
     * @param subscriptionId The receiving SIM subscription, or [SmsSender.UNKNOWN_SUBSCRIPTION_ID].
     */
    private fun enqueueLookup(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
    ) {
        val input = Data.Builder()
            .putString(SmsLookupWorker.KEY_SENDER_ADDRESS, sender)
            .putString(SmsLookupWorker.KEY_MESSAGE_BODY, body)
            .putLong(SmsLookupWorker.KEY_RECEIVED_AT, receivedAt)
            .putInt(SmsLookupWorker.KEY_SUBSCRIPTION_ID, subscriptionId)
            .build()

        val request = OneTimeWorkRequestBuilder<SmsLookupWorker>()
            .setInputData(input)
            // Expedited so detection is immediate even in Doze or battery saver. The out-of-quota
            // policy degrades to normal work rather than throwing once the app's expedited
            // allowance is exhausted.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(SmsLookupWorker.WORK_NAME_PREFIX)
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Enqueued lookup for a ${body.length}-character message")
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue lookup worker", error)
        }
    }

    private companion object {
        /** Logcat tag for this class. */
        const val TAG = "SmsReceiver"

        /**
         * The pre-`SubscriptionManager` extra key some OEM telephony stacks still use for the
         * receiving subscription id. Values are the same non-negative subscription ids as the
         * modern key; absent means unknown.
         */
        const val LEGACY_SUBSCRIPTION_EXTRA = "subscription"
    }
}
