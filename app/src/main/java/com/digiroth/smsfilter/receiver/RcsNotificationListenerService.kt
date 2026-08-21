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

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digiroth.smsfilter.data.db.entity.MessageSource
import com.digiroth.smsfilter.platform.AndroidDirectReplySender
import com.digiroth.smsfilter.worker.SmsProcessingPipeline
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Extracted message payload from an incoming messaging notification.
 *
 * @property sender The originating phone number, short code, or contact name.
 * @property body The message text body.
 * @property messageSource The classified transport protocol ([MessageSource.RCS] or [MessageSource.MMS]).
 * @property isGroupThread Whether the notification originated from a group conversation.
 */
data class NotificationMessageData(
    val sender: String,
    val body: String,
    val messageSource: MessageSource,
    val isGroupThread: Boolean,
)

/**
 * Listens for incoming messaging notifications from RCS and MMS capable apps (Google Messages, Samsung Messages)
 * to detect opt-out requests and execute direct inline auto-replies via RemoteInput PendingIntents.
 */
@AndroidEntryPoint
class RcsNotificationListenerService : NotificationListenerService() {

    /** The business logic pipeline for processing incoming messages. */
    @Inject
    lateinit var pipeline: SmsProcessingPipeline

    /** Direct reply sender handle registry for inline notification replies. */
    @Inject
    lateinit var directReplySender: AndroidDirectReplySender

    /** Coroutine scope bound to the service lifecycle for asynchronous message processing. */
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Cleans up resources and cancels active background coroutines when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * Called when a new notification is posted by the Android system.
     *
     * @param sbn The status bar notification wrapper, or `null`.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        if ((packageName != PACKAGE_GOOGLE_MESSAGES) && (packageName != PACKAGE_SAMSUNG_MESSAGES)) {
            return
        }

        val notification = sbn.notification ?: return

        val directReplyAction = notification.actions?.firstOrNull { action ->
            (action.remoteInputs != null) && action.remoteInputs.isNotEmpty() && (action.actionIntent != null)
        } ?: return

        val messageData = extractNotificationMessage(notification) ?: run {
            Log.w(TAG, "Could not extract sender or body from messaging notification")
            return
        }

        val sender = messageData.sender
        val body = messageData.body
        if (sender.isBlank() || body.isBlank()) {
            return
        }

        if (body.endsWith("…") || body.endsWith("...")) {
            Log.w(TAG, "Notification message body may be truncated")
        }

        val replyKey = UUID.randomUUID().toString()
        val remoteInput = directReplyAction.remoteInputs.first()
        directReplySender.registerHandle(replyKey, directReplyAction.actionIntent, remoteInput)

        serviceScope.launch {
            runCatching {
                val outcome = pipeline.process(
                    senderAddress = sender,
                    messageBody = body,
                    receivedAtMillis = sbn.postTime,
                    directReplyKey = replyKey,
                    messageSource = messageData.messageSource,
                    isGroupThread = messageData.isGroupThread,
                )
                Log.d(TAG, "Processed notification message with outcome: ${outcome::class.simpleName}")
            }.onFailure { error ->
                Log.e(TAG, "Error processing notification message", error)
            }
        }
    }

    /**
     * Extracts message data, sender address, and transport classification (RCS vs MMS) from a notification.
     *
     * @param notification The posted [Notification] instance to parse.
     * @return The extracted [NotificationMessageData], or `null` if the notification lacks usable text or sender.
     */
    private fun extractNotificationMessage(notification: Notification): NotificationMessageData? {
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if ((messagingStyle != null) && messagingStyle.messages.isNotEmpty()) {
            val isGroupConversation = messagingStyle.isGroupConversation
            val hasAttachment = messagingStyle.messages.any { (it.dataMimeType != null) || (it.dataUri != null) } ||
                notification.extras.containsKey(Notification.EXTRA_PICTURE) ||
                (notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("BigPictureStyle") == true)

            val messageSource = if (isGroupConversation || hasAttachment) {
                MessageSource.MMS
            } else {
                MessageSource.RCS
            }

            val latestMessage = messagingStyle.messages.lastOrNull() ?: return null
            val body = latestMessage.text?.toString()?.trim() ?: return null
            val person = latestMessage.person
            val senderKey = person?.key?.removePrefix(TEL_PREFIX)
            val senderName = person?.name?.toString()
            val fallbackTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val sender = (senderKey ?: senderName ?: fallbackTitle)?.trim() ?: return null

            return NotificationMessageData(
                sender = sender,
                body = body,
                messageSource = messageSource,
                isGroupThread = isGroupConversation,
            )
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return null
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val body = bigText ?: text ?: return null
        val hasAttachment = extras.containsKey(Notification.EXTRA_PICTURE) ||
            (extras.getString(Notification.EXTRA_TEMPLATE)?.contains("BigPictureStyle") == true)
        val messageSource = if (hasAttachment) MessageSource.MMS else MessageSource.RCS

        return NotificationMessageData(
            sender = title,
            body = body,
            messageSource = messageSource,
            isGroupThread = false,
        )
    }

    companion object {
        /** Tag for logcat output. */
        private const val TAG: String = "RcsNotificationListener"

        /** Package name for Google Messages. */
        const val PACKAGE_GOOGLE_MESSAGES: String = "com.google.android.apps.messaging"

        /** Package name for Samsung Messages. */
        const val PACKAGE_SAMSUNG_MESSAGES: String = "com.samsung.android.messaging"

        /** Prefix for URI-based telephone keys in person metadata. */
        private const val TEL_PREFIX: String = "tel:"
    }
}
