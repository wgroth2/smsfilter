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

package com.digiroth.smsfilter.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.SmsFilterApplication
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the SMS decision pipeline for one incoming message.
 *
 * This class is a deliberately thin adapter: it unpacks the worker's input [androidx.work.Data],
 * delegates every decision to [SmsProcessingPipeline], and maps the outcome onto a WorkManager
 * [Result]. Keeping the logic in the pipeline is what allows it to be unit-tested on the JVM,
 * since a worker itself cannot be.
 *
 * Enqueued as an expedited request by `SmsReceiver`. On API levels below 31 expedited work runs as
 * a foreground service, which is why [getForegroundInfo] is overridden — without it WorkManager
 * raises `IllegalStateException` on those devices.
 */
@HiltWorker
class SmsLookupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: SmsProcessingPipeline,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER_ADDRESS)
        val body = inputData.getString(KEY_MESSAGE_BODY)
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, 0L)

        if (sender.isNullOrBlank() || body == null) {
            // Malformed input can never succeed on retry, so fail permanently rather than
            // occupying the expedited quota repeatedly.
            Log.e(TAG, "Missing sender or body in worker input; dropping message")
            return Result.failure()
        }

        return runCatching {
            val outcome = pipeline.process(
                senderAddress = sender,
                messageBody = body,
                receivedAtMillis = if (receivedAt > 0L) receivedAt else System.currentTimeMillis(),
            )
            Log.d(TAG, "Processing outcome: ${outcome::class.simpleName}")
            Result.success()
        }.getOrElse { error ->
            // A transient database or network fault is worth one retry; WorkManager applies its own
            // backoff. Note the pipeline already swallows send and playback failures internally, so
            // reaching here does not imply a duplicate reply.
            Log.e(TAG, "Pipeline failed; requesting retry", error)
            Result.retry()
        }
    }

    /**
     * Supplies the transient notification shown when expedited work falls back to a foreground
     * service on API < 31.
     *
     * @return Foreground info using the low-importance status channel, tagged `dataSync` to match
     *   the service type declared in the manifest.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, SmsFilterApplication.CHANNEL_ID_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(applicationContext.getString(R.string.notification_worker_status_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_STATUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID_STATUS, notification)
        }
    }

    companion object {
        private const val TAG = "SmsLookupWorker"

        /** Notification id for the expedited-fallback status notice. */
        private const val NOTIFICATION_ID_STATUS = 2002

        /** Input key: the raw originating address. */
        const val KEY_SENDER_ADDRESS: String = "sender_address"

        /** Input key: the fully reconstructed message body. */
        const val KEY_MESSAGE_BODY: String = "message_body"

        /** Input key: message arrival time in epoch milliseconds. */
        const val KEY_RECEIVED_AT: String = "received_at"

        /** Unique work name prefix, used to keep concurrent messages independent. */
        const val WORK_NAME_PREFIX: String = "sms_lookup"
    }
}
