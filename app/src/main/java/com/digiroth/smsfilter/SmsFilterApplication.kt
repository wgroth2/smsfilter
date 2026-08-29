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

package com.digiroth.smsfilter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point for the SMS Compliance Filter.
 *
 * For official Android documentation on the architectural patterns used here, see:
 * - WorkManager with Hilt: [https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)
 * - Custom WorkManager configuration: [https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration](https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration)
 * - Notification channels: [https://developer.android.com/develop/ui/views/notifications/channels](https://developer.android.com/develop/ui/views/notifications/channels)
 *
 * Two responsibilities are handled here, both of which must happen before any other
 * component runs:
 *
 * 1. **Hilt-aware WorkManager initialization.** `SmsLookupWorker` is a `@HiltWorker` with
 *    an `@AssistedInject` constructor, so WorkManager's default self-initialization is
 *    removed in the manifest and replaced by the [Configuration] built here from the
 *    injected [HiltWorkerFactory]. Without this the worker would be instantiated
 *    reflectively without its dependencies and crash at runtime, with no compile-time
 *    warning.
 * 2. **Notification channel registration.** Channels must exist before the first
 *    notification is posted; the detection alert and the expedited worker's transient
 *    foreground notice each use their own channel so the user can tune them separately.
 */
@HiltAndroidApp
class SmsFilterApplication : Application(), Configuration.Provider {

    /**
     * Hilt-provided factory that lets WorkManager construct `@HiltWorker` instances with
     * their dependencies injected. Populated by Hilt before [onCreate] runs.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * WorkManager configuration used in place of the default initializer.
     *
     * @return a [Configuration] wired to the Hilt worker factory, with verbose WorkManager
     *   logging enabled only in debug builds.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /**
     * Performs process-level initialization on application startup, creating all required
     * notification channels.
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application starting; registering notification channels")
        createNotificationChannels()
    }

    /**
     * Registers the app's notification channels.
     *
     * Re-registering an existing channel is a no-op, so this is safe to call on every
     * process start. Creating a channel never throws, but the call is guarded anyway so a
     * failure here can never prevent the application from starting.
     */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager == null) {
            Log.w(TAG, "NotificationManager unavailable; channels not registered")
            return
        }

        runCatching {
            val detections = NotificationChannel(
                CHANNEL_ID_DETECTIONS,
                getString(R.string.notification_channel_detections_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notification_channel_detections_description)
            }

            val status = NotificationChannel(
                CHANNEL_ID_STATUS,
                getString(R.string.notification_channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_status_description)
                setShowBadge(false)
            }

            manager.createNotificationChannel(detections)
            manager.createNotificationChannel(status)
        }.onFailure { error ->
            Log.e(TAG, "Failed to register notification channels", error)
        }
    }

    companion object {
        /** Logging tag for this class, per the project's structured-logging convention. */
        private const val TAG = "SmsFilterApplication"

        /**
         * Channel for high-priority opt-out detection alerts. Referenced by the detection
         * notification raised in [com.digiroth.smsfilter.worker] once that phase exists.
         */
        const val CHANNEL_ID_DETECTIONS = "optout_detections"

        /**
         * Channel for the transient notification shown when an expedited work request
         * falls back to foreground execution on API levels below 31. Low importance so it
         * is silent and carries no badge.
         */
        const val CHANNEL_ID_STATUS = "background_status"
    }
}
