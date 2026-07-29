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

package com.digiroth.smsfilter.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.SmsFilterApplication
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routing keys shared between the notifications raised here and the Activity that handles a tap.
 *
 * Declared in this neutral place so the onboarding/UI phase can read them without this file
 * needing to change, and without either side hardcoding a string the other might mistype.
 */
object NotificationRoute {

    /** Intent extra naming the screen a notification tap should open. */
    const val EXTRA_OPEN_SCREEN: String = "com.digiroth.smsfilter.extra.OPEN_SCREEN"

    /** [EXTRA_OPEN_SCREEN] value for the activity and detection log. */
    const val SCREEN_DETECTION_LOG: String = "detection_log"

    /** [EXTRA_OPEN_SCREEN] value for the settings screen. */
    const val SCREEN_SETTINGS: String = "settings"
}

/**
 * Posts the user-facing notification when an opt-out request is detected.
 *
 * Behind an interface so the pipeline can assert that the notification fires on every detection
 * — including detections where all three auto-reply gates blocked the send. The specification is
 * explicit that a blocked reply is still surfaced to the user.
 */
fun interface DetectionNotifier {

    /**
     * Shows the "Opt-out request detected" notification.
     *
     * @param messagePreview A short excerpt of the message body. Must not contain a phone number.
     */
    fun notifyOptOutDetected(messagePreview: String)
}

/**
 * The production [DetectionNotifier].
 *
 * The content intent is resolved through the package's launcher intent rather than by naming the
 * Activity class. That keeps this file free of a compile-time dependency on a class the UI phase
 * has not created yet, so it never needs editing once the Activity exists — the manifest's
 * existing LAUNCHER filter is what makes the lookup work.
 */
@Singleton
class AndroidDetectionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : DetectionNotifier {

    override fun notifyOptOutDetected(messagePreview: String) {
        if (!hasPostPermission()) {
            // POST_NOTIFICATIONS is a runtime permission from API 33. Onboarding requires it, but
            // it can be revoked later from system settings; detection must continue regardless.
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping detection notification")
            return
        }

        runCatching {
            val notification = NotificationCompat.Builder(context, SmsFilterApplication.CHANNEL_ID_DETECTIONS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(context.getString(R.string.notification_detection_title))
                .setContentText(messagePreview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messagePreview))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(detectionLogIntent())
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DETECTION, notification)
        }.onFailure { error ->
            Log.e(TAG, "Failed to post detection notification", error)
        }
    }

    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun detectionLogIntent(): PendingIntent? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.putExtra(NotificationRoute.EXTRA_OPEN_SCREEN, NotificationRoute.SCREEN_DETECTION_LOG)
            ?: return null

        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_DETECTION,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "AndroidDetectionNotifier"

        /** Fixed id so consecutive detections replace rather than stack up. */
        const val NOTIFICATION_ID_DETECTION = 1001

        /** Request code for the detection notification's content intent. */
        const val REQUEST_CODE_DETECTION = 2001
    }
}
