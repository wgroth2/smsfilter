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

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.digiroth.smsfilter.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android framework implementation of [DirectReplySender].
 *
 * For official Android documentation on RemoteInput and Direct Reply notification actions, see:
 * - Direct Reply Actions: [https://developer.android.com/develop/ui/views/notifications/actions#direct-reply](https://developer.android.com/develop/ui/views/notifications/actions#direct-reply)
 *
 * Maintains an in-memory cache of ephemeral direct reply handles captured from
 * incoming RCS messaging notifications, and dispatches direct replies through
 * the registered [PendingIntent] with [RemoteInput] results.
 *
 * @param context Application context used for sending pending intents.
 * @param timeProvider Clock source for expiring cached handles.
 */
@Singleton
class AndroidDirectReplySender @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
) : DirectReplySender {

    /** Synchronization lock for handle storage access. */
    private val lock: Any = Any()

    /** Ephemeral in-memory registry of direct reply handles keyed by unique token. */
    private val handles: MutableMap<String, DirectReplyHandle> = mutableMapOf()

    /**
     * Registers a direct reply handle for a notification action.
     *
     * @param key Ephemeral identifier for the action.
     * @param pendingIntent The action's [PendingIntent] to execute when replying.
     * @param remoteInput The [RemoteInput] specification describing where the reply text is placed.
     */
    fun registerHandle(key: String, pendingIntent: PendingIntent, remoteInput: RemoteInput): Unit = synchronized(lock) {
        pruneExpired(timeProvider.nowMillis())
        handles[key] = DirectReplyHandle(
            pendingIntent = pendingIntent,
            remoteInput = remoteInput,
            registeredAtMillis = timeProvider.nowMillis(),
        )
        Log.d(TAG, "Registered direct reply handle")
    }

    /**
     * Executes the direct reply action with the given opt-out response body.
     *
     * @param replyKey Ephemeral identifier for the direct reply action.
     * @param body The opt-out reply text.
     * @return `true` if the pending intent was dispatched without error, `false` otherwise.
     */
    override fun sendDirectReply(replyKey: String, body: String): Boolean {
        val handle = synchronized(lock) {
            pruneExpired(timeProvider.nowMillis())
            handles.remove(replyKey)
        } ?: run {
            Log.w(TAG, "Direct reply handle not found or expired")
            return false
        }

        return runCatching {
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(handle.remoteInput.resultKey, body)
            }
            RemoteInput.addResultsToIntent(arrayOf(handle.remoteInput), intent, bundle)
            handle.pendingIntent.send(context, 0, intent)
            Log.d(TAG, "Dispatched direct reply via PendingIntent")
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to send direct reply via PendingIntent", error)
        }.getOrDefault(defaultValue = false)
    }

    /**
     * Prunes cached direct reply handles that have exceeded [HANDLE_TTL_MILLIS].
     *
     * @param now Current timestamp in epoch milliseconds.
     */
    private fun pruneExpired(now: Long) {
        val cutoff = now - HANDLE_TTL_MILLIS
        handles.entries.removeIf { it.value.registeredAtMillis < cutoff }
    }

    /**
     * Ephemeral container holding the pending intent and remote input required to send an inline reply.
     *
     * @property pendingIntent The notification action pending intent.
     * @property remoteInput The remote input specification describing the reply input key.
     * @property registeredAtMillis Epoch timestamp when this handle was registered.
     */
    private data class DirectReplyHandle(
        val pendingIntent: PendingIntent,
        val remoteInput: RemoteInput,
        val registeredAtMillis: Long,
    )

    private companion object {
        const val TAG: String = "AndroidDirectReplySender"

        /** Expiration time-to-live for a cached direct reply handle in milliseconds. */
        const val HANDLE_TTL_MILLIS: Long = 60_000L
    }
}
