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
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digiroth.smsfilter.data.db.entity.MessageSource
import com.digiroth.smsfilter.platform.AndroidDirectReplySender
import com.digiroth.smsfilter.platform.MmsTextResolver
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
 *
 * For official Android documentation on notification listening and messaging styles, see:
 * - NotificationListenerService: [https://developer.android.com/reference/android/service/notification/NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
 * - MessagingStyle Notifications: [https://developer.android.com/develop/ui/views/notifications/expanded#message-style](https://developer.android.com/develop/ui/views/notifications/expanded#message-style)
 */
@AndroidEntryPoint
class RcsNotificationListenerService : NotificationListenerService() {

    /** The business logic pipeline for processing incoming messages. */
    @Inject
    lateinit var pipeline: SmsProcessingPipeline

    /** Direct reply sender handle registry for inline notification replies. */
    @Inject
    lateinit var directReplySender: AndroidDirectReplySender

    /** Telephony MMS storage resolver for recovering full message bodies. */
    @Inject
    lateinit var mmsTextResolver: MmsTextResolver

    /** Decides which of a notification's text sources is the message that just arrived. */
    @Inject
    lateinit var bodyAssembler: NotificationBodyAssembler

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
                var messageBody = body
                if ((messageData.messageSource == MessageSource.MMS) ||
                    messageBody.startsWith("Image") ||
                    messageBody.startsWith("Photo") ||
                    messageBody.endsWith("…") ||
                    messageBody.endsWith("...")
                ) {
                    val resolvedFullText = mmsTextResolver.resolveFullMmsTextWithRetry(messageBody)
                    if ((resolvedFullText != null) &&
                        ((resolvedFullText.length > messageBody.length) ||
                            messageBody.startsWith("Image") ||
                            messageBody.startsWith("Photo"))
                    ) {
                        messageBody = resolvedFullText
                        Log.d(TAG, "Resolved full MMS body from telephony provider (${messageBody.length} chars)")
                    }
                }

                val outcome = pipeline.process(
                    senderAddress = sender,
                    messageBody = messageBody,
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
     * Examines both AndroidX [NotificationCompat.MessagingStyle] and native [Notification.EXTRA_MESSAGES]
     * bundles to avoid truncation from platform version or styling differences.
     *
     * @param notification The posted [Notification] instance to parse.
     * @return The extracted [NotificationMessageData], or `null` if the notification lacks usable text or sender.
     */
    @Suppress("DEPRECATION")
    private fun extractNotificationMessage(notification: Notification): NotificationMessageData? {
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val rawMessages: Array<Parcelable>? = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: notification.extras.getParcelableArray("android.messages")

        // Identifies the user's own entries so neither the sender nor the body is taken from a
        // message they sent themselves.
        val selfKey: String? = messagingStyle?.user?.key

        val sender: String? = run {
            // The last message with an author, not simply the last message: the newest entry may
            // be one the user sent, whose person is absent or is the style's own user.
            val stylePerson = messagingStyle?.messages
                ?.lastOrNull { message ->
                    val person = message.person
                    (person != null) && ((selfKey == null) || (person.key != selfKey))
                }
                ?.person
            val styleKey = stylePerson?.key?.removePrefix(TEL_PREFIX)
            val styleName = stylePerson?.name?.toString()
            if (!styleKey.isNullOrBlank() || !styleName.isNullOrBlank()) {
                return@run (styleKey ?: styleName)?.trim()
            }

            val lastRawBundle = rawMessages?.lastOrNull() as? Bundle
            val rawSender = lastRawBundle?.getCharSequence("sender")?.toString()?.trim()
            if (!rawSender.isNullOrBlank()) {
                return@run rawSender
            }

            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        }

        if (sender.isNullOrBlank()) {
            return null
        }

        // Collect fragments with their authorship. Only the newest incoming one is the message
        // that just arrived — see NotificationBodyAssembler for why joining them all was wrong.
        val messageFragments = mutableListOf<NotificationFragment>()

        messagingStyle?.messages?.forEach { message ->
            val fragmentText = message.text?.toString()?.trim()
            if (!fragmentText.isNullOrEmpty() && messageFragments.none { it.text == fragmentText }) {
                // MessagingStyle marks the user's own messages either by omitting the person or by
                // naming the style's own user.
                val person = message.person
                val isFromSelf = (person == null) || ((selfKey != null) && (person.key == selfKey))
                messageFragments.add(NotificationFragment(text = fragmentText, isFromSelf = isFromSelf))
            }
        }

        // The raw bundle array is consulted only when MessagingStyle yielded nothing. Appending it
        // to fragments already collected would interleave two orderings, and the assembler depends
        // on the last incoming fragment genuinely being the newest.
        if (messageFragments.isEmpty()) {
            rawMessages?.forEach { item ->
                if (item is Bundle) {
                    val fragmentText = item.getCharSequence("text")?.toString()?.trim()
                    if (!fragmentText.isNullOrEmpty() && messageFragments.none { it.text == fragmentText }) {
                        // A raw message bundle with no "sender" entry is one the user sent.
                        val isFromSelf = item.getCharSequence("sender") == null
                        messageFragments.add(
                            NotificationFragment(text = fragmentText, isFromSelf = isFromSelf),
                        )
                    }
                }
            }
        }

        val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.ifEmpty { null }
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.ifEmpty { null }
        val textLines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.filterNotNull()
            ?.map { it.toString().trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.ifEmpty { null }

        val body = bodyAssembler.assemble(
            fragments = messageFragments,
            bigText = bigText,
            text = text,
            textLines = textLines,
        ) ?: return null
        if (body.isBlank()) {
            return null
        }

        val isGroupConversation = messagingStyle?.isGroupConversation
            ?: notification.extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false)
        val hasAttachment = hasNotificationAttachment(notification)

        val messageSource = if (isGroupConversation || hasAttachment) {
            MessageSource.MMS
        } else {
            MessageSource.RCS
        }

        return NotificationMessageData(
            sender = sender,
            body = body,
            messageSource = messageSource,
            isGroupThread = isGroupConversation,
        )
    }

    /**
     * Determines whether a notification contains media or attachment indicators.
     *
     * @param notification The notification to inspect.
     * @return `true` if the notification references media, images, or non-text message parts.
     */
    @Suppress("DEPRECATION")
    private fun hasNotificationAttachment(notification: Notification): Boolean {
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val rawMessages: Array<Parcelable>? = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: notification.extras.getParcelableArray("android.messages")

        return (messagingStyle?.messages?.any { (it.dataMimeType != null) || (it.dataUri != null) } == true) ||
            (rawMessages?.any { (it as? Bundle)?.getString("data_mime_type") != null } == true) ||
            notification.extras.containsKey(Notification.EXTRA_PICTURE) ||
            (notification.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("BigPictureStyle") == true)
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

        /** Notification extra key for group conversation flag. */
        private const val EXTRA_IS_GROUP_CONVERSATION: String = "android.isGroupConversation"
    }
}
