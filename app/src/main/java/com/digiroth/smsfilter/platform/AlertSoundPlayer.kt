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

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the audible confirmation after an opt-out reply is sent.
 *
 * Behind an interface so the pipeline can assert that sound plays only when "Beep On Opt-Out"
 * is enabled *and* a reply was actually sent — the specification requires silence when either
 * condition fails.
 */
fun interface AlertSoundPlayer {

    /**
     * Plays the opt-out alert sound.
     *
     * @param soundFileUri The user-selected sound URI as a string, or `null`/blank to use the
     *   system notification sound.
     */
    fun playOptOutAlert(soundFileUri: String?)
}

/**
 * The production [AlertSoundPlayer].
 *
 * Playback is best-effort by design. A user-selected URI can become unreadable between
 * selection and use — the app process may have been restarted without a persisted URI grant,
 * or the file may have been deleted — so every failure is swallowed after logging. A missing
 * beep is a cosmetic loss; an exception escaping here would fail the worker after a reply had
 * already been sent, and a WorkManager retry could then send a second one.
 */
@Singleton
class AndroidAlertSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertSoundPlayer {

    override fun playOptOutAlert(soundFileUri: String?) {
        runCatching {
            val uri: Uri = soundFileUri
                ?.takeIf(String::isNotBlank)
                ?.let(Uri::parse)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val ringtone = RingtoneManager.getRingtone(context, uri)
            if (ringtone == null) {
                Log.w(TAG, "No ringtone resolved for the configured sound; playing nothing")
                return@runCatching
            }
            ringtone.play()
        }.onFailure { error ->
            Log.w(TAG, "Failed to play opt-out alert sound; continuing", error)
        }
    }

    private companion object {
        const val TAG = "AndroidAlertSoundPlayer"
    }
}
