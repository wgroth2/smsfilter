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

package com.digiroth.smsfilter.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted storage for the HubSpot Private App access token.
 *
 * The token is the only secret the app holds. It is entered by the user at runtime and never
 * appears in source, `BuildConfig`, `local.properties`, or version control. Private App
 * tokens do not expire, so there is no refresh logic — the token is written once when the
 * user connects and cleared only when they explicitly disconnect.
 *
 * `androidx.security:security-crypto` is deprecated upstream and `1.1.0-alpha06` is its final
 * release. That is a deliberate, accepted choice for this single-user sideloaded app: the
 * library remains fully functional on API 26–35, and the specification forbids substituting a
 * different secure-storage dependency.
 *
 * Every operation is failure-tolerant. Keystore access can fail on a device whose keys were
 * invalidated (a factory reset or a restored backup), and losing the token must degrade to
 * "HubSpot not connected" rather than crash the SMS pipeline or the Settings screen.
 *
 * @property context Application context used to open the encrypted preferences file.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The encrypted preferences file, opened on first use.
     *
     * Lazy rather than eager because opening it performs Keystore work, and this class is a
     * Hilt singleton that may be constructed on a path that never touches HubSpot at all.
     * `null` means the file could not be opened; callers then behave as if no token exists.
     */
    private val preferences: SharedPreferences? by lazy { openEncryptedPreferences() }

    /**
     * Reads the stored access token.
     *
     * @return The token, or `null` if none is stored or the encrypted store is unavailable.
     */
    fun getAccessToken(): String? = runCatching {
        preferences?.getString(KEY_ACCESS_TOKEN, null)?.takeIf(String::isNotBlank)
    }.onFailure { error ->
        Log.e(TAG, "Failed to read access token", error)
    }.getOrNull()

    /**
     * Persists the access token.
     *
     * @param token The HubSpot Private App access token.
     * @return `true` if the token was written, `false` if the encrypted store was unavailable
     *   or the write failed.
     */
    fun saveAccessToken(token: String): Boolean = runCatching {
        val prefs = preferences ?: return@runCatching false
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).commit()
    }.onFailure { error ->
        Log.e(TAG, "Failed to persist access token", error)
    }.getOrDefault(false)

    /**
     * Deletes the stored access token. Invoked by the explicit "Disconnect" action — turning
     * the HubSpot toggle off deliberately retains the token so it can be re-enabled without
     * re-entry.
     *
     * @return `true` if the token was removed or was already absent.
     */
    fun clearAccessToken(): Boolean = runCatching {
        val prefs = preferences ?: return@runCatching false
        prefs.edit().remove(KEY_ACCESS_TOKEN).commit()
    }.onFailure { error ->
        Log.e(TAG, "Failed to clear access token", error)
    }.getOrDefault(false)

    /**
     * Whether a token is currently stored. Drives the "Setup incomplete" state in the
     * Connection Health Summary, where the HubSpot toggle is on but no token has been saved.
     *
     * @return `true` if a non-blank token is present.
     */
    fun hasAccessToken(): Boolean = getAccessToken() != null

    private fun openEncryptedPreferences(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { error ->
        Log.e(TAG, "Failed to open encrypted preferences; treating HubSpot as disconnected", error)
    }.getOrNull()

    companion object {
        /** Logging tag for this class. Never log the token itself. */
        private const val TAG = "SecureTokenStore"

        /** Name of the encrypted preferences file. */
        const val PREFS_FILE_NAME: String = "smsfilter_secure_prefs"

        /** Preference key holding the HubSpot Private App access token. */
        const val KEY_ACCESS_TOKEN: String = "hubspot_access_token"
    }
}
