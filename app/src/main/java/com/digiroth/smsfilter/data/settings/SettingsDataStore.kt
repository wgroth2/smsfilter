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

package com.digiroth.smsfilter.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Health of one external data source, surfaced as a coloured dot in the Connection Health
 * Summary. Persisted so the UI can render immediately on recomposition without re-running a
 * live check.
 */
enum class ConnectionStatus {
    /** No check has run yet since installation. */
    UNKNOWN,

    /** Deliberately switched off by the user; must never be styled as an error. */
    OFF,

    /** Enabled but not yet usable — e.g. the HubSpot toggle is on but no token is saved. */
    SETUP_INCOMPLETE,

    /** Last check succeeded. */
    CONNECTED,

    /** Last check failed for a non-authentication reason (permission denied, unreachable). */
    DISCONNECTED,

    /** Last call was rejected with HTTP 401 — token revoked or scope removed. */
    AUTH_ERROR,
    ;

    companion object {
        /**
         * Parses a persisted status name, tolerating unrecognized input.
         *
         * Unlike the Room converters, this deliberately falls back rather than throwing: a
         * stale or unknown value in a preferences file must not be able to crash the app on
         * startup, and [UNKNOWN] is a safe, self-correcting default because the next live
         * check overwrites it.
         *
         * @param value The stored enum name, or `null`.
         * @return The matching status, or [UNKNOWN].
         */
        fun fromStoredValue(value: String?): ConnectionStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** The single `DataStore<Preferences>` instance for the process, created lazily on first use. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsDataStore.STORE_NAME,
)

/**
 * The single store for every scalar setting and flag in the app.
 *
 * For official Android documentation on Jetpack DataStore Preferences, see:
 * - DataStore: [https://developer.android.com/topic/libraries/architecture/datastore](https://developer.android.com/topic/libraries/architecture/datastore)
 *
 * This class is the whole settings surface: application configuration, onboarding flags, and
 * persisted connection health all live here. There is intentionally **no** settings table in
 * Room, so later phases inject exactly one type to read or write any preference.
 *
 * Each value is exposed twice: as a [Flow] for reactive UI collection, and via a suspending
 * one-shot getter for `SmsLookupWorker`, which needs a snapshot at the moment a message
 * arrives rather than a subscription. Reads recover from [IOException] by falling back to
 * defaults, so a corrupt preferences file degrades to first-run behaviour instead of
 * crashing the SMS pipeline.
 *
 * @property context Application context supplying the backing DataStore.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dataStore: DataStore<Preferences> = context.settingsDataStore

    /** Every preference read, with defaults applied and IO failures neutralised. */
    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Log.e(TAG, "Failed to read preferences; falling back to defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    // ---------------------------------------------------------------------
    // Auto-reply
    // ---------------------------------------------------------------------

    /**
     * Master auto-reply switch. When `false` the app runs in detection-only (dry run) mode:
     * it detects, notifies, and logs, but never sends an SMS.
     */
    val autoReplyEnabled: Flow<Boolean> =
        preferences.map { it[KEY_AUTO_REPLY_ENABLED] ?: DEFAULT_AUTO_REPLY_ENABLED }

    /**
     * Sets the master auto-reply switch.
     *
     * @param enabled `true` to send replies, `false` for detection-only mode.
     */
    suspend fun setAutoReplyEnabled(enabled: Boolean) {
        write(KEY_AUTO_REPLY_ENABLED, enabled)
    }

    // ---------------------------------------------------------------------
    // HubSpot
    // ---------------------------------------------------------------------

    /** Whether HubSpot lookups are enabled. Off by default; HubSpot is entirely optional. */
    val useHubSpot: Flow<Boolean> =
        preferences.map { it[KEY_USE_HUBSPOT] ?: DEFAULT_USE_HUBSPOT }

    /**
     * Enables or disables all HubSpot logic.
     *
     * @param enabled `true` to consult HubSpot during lookups.
     */
    suspend fun setUseHubSpot(enabled: Boolean) {
        write(KEY_USE_HUBSPOT, enabled)
    }

    // ---------------------------------------------------------------------
    // Sound
    // ---------------------------------------------------------------------

    /** Whether to play a sound after an opt-out reply is actually sent. */
    val beepOnOptOut: Flow<Boolean> =
        preferences.map { it[KEY_BEEP_ON_OPT_OUT] ?: DEFAULT_BEEP_ON_OPT_OUT }

    /**
     * Sets whether a sound plays after a reply is sent.
     *
     * @param enabled `true` to play the configured sound.
     */
    suspend fun setBeepOnOptOut(enabled: Boolean) {
        write(KEY_BEEP_ON_OPT_OUT, enabled)
    }

    /**
     * URI of the sound to play, or `null` to use the system notification sound. Stored as a
     * string because DataStore has no URI type.
     */
    val soundFileUri: Flow<String?> =
        preferences.map { it[KEY_SOUND_FILE_URI]?.takeIf(String::isNotBlank) }

    /**
     * Sets the beep sound.
     *
     * @param uri The sound URI as a string, or `null`/blank to fall back to the system sound.
     */
    suspend fun setSoundFileUri(uri: String?) {
        write(KEY_SOUND_FILE_URI, uri.orEmpty())
    }

    // ---------------------------------------------------------------------
    // Notifications & language
    // ---------------------------------------------------------------------

    /** Whether the high-priority "Opt-out request detected" notification is shown. */
    val optOutNotificationEnabled: Flow<Boolean> =
        preferences.map { it[KEY_OPT_OUT_NOTIFICATION_ENABLED] ?: DEFAULT_OPT_OUT_NOTIFICATION_ENABLED }

    /**
     * Sets whether detection notifications are shown.
     *
     * @param enabled `true` to show a notification on each detection.
     */
    suspend fun setOptOutNotificationEnabled(enabled: Boolean) {
        write(KEY_OPT_OUT_NOTIFICATION_ENABLED, enabled)
    }

    /** Selected UI language as an ISO 639-1 code; `"en"` or `"es"`. */
    val appLanguage: Flow<String> =
        preferences.map { it[KEY_APP_LANGUAGE] ?: DEFAULT_APP_LANGUAGE }

    /**
     * Sets the UI language.
     *
     * @param languageCode An ISO 639-1 code, `"en"` or `"es"`.
     */
    suspend fun setAppLanguage(languageCode: String) {
        write(KEY_APP_LANGUAGE, languageCode)
    }

    // ---------------------------------------------------------------------
    // Onboarding flags
    // ---------------------------------------------------------------------

    /**
     * Whether the setup wizard has completed.
     *
     * This is the onboarding gate: `SmsLookupWorker` reads it first and exits immediately
     * while it is `false`, because the manifest-declared receiver goes live as soon as
     * `RECEIVE_SMS` is granted mid-wizard.
     */
    val firstRunComplete: Flow<Boolean> =
        preferences.map { it[KEY_FIRST_RUN_COMPLETE] ?: DEFAULT_FIRST_RUN_COMPLETE }

    /**
     * Marks the setup wizard complete or incomplete.
     *
     * @param complete `true` once the wizard's final step is confirmed.
     */
    suspend fun setFirstRunComplete(complete: Boolean) {
        write(KEY_FIRST_RUN_COMPLETE, complete)
    }

    /**
     * Whether the one-time post-onboarding "Connect HubSpot CRM?" dialog has been shown.
     * Set by every dismissal path, so the dialog appears at most once ever.
     */
    val hubSpotPromptShown: Flow<Boolean> =
        preferences.map { it[KEY_HUBSPOT_PROMPT_SHOWN] ?: DEFAULT_HUBSPOT_PROMPT_SHOWN }

    /**
     * Records that the one-time HubSpot prompt has been shown.
     *
     * @param shown `true` once the dialog has been presented and dismissed by any route.
     */
    suspend fun setHubSpotPromptShown(shown: Boolean) {
        write(KEY_HUBSPOT_PROMPT_SHOWN, shown)
    }

    // ---------------------------------------------------------------------
    // Connection health
    // ---------------------------------------------------------------------

    /** Persisted health of the local Google Contacts lookup. */
    val googleContactsStatus: Flow<ConnectionStatus> =
        preferences.map { ConnectionStatus.fromStoredValue(it[KEY_GOOGLE_CONTACTS_STATUS]) }

    /**
     * Records the outcome of a Google Contacts check.
     *
     * @param status The status to persist.
     */
    suspend fun setGoogleContactsStatus(status: ConnectionStatus) {
        write(KEY_GOOGLE_CONTACTS_STATUS, status.name)
    }

    /** Persisted health of the HubSpot connection. */
    val hubSpotStatus: Flow<ConnectionStatus> =
        preferences.map { ConnectionStatus.fromStoredValue(it[KEY_HUBSPOT_STATUS]) }

    /**
     * Records the outcome of a HubSpot check.
     *
     * @param status The status to persist.
     */
    suspend fun setHubSpotStatus(status: ConnectionStatus) {
        write(KEY_HUBSPOT_STATUS, status.name)
    }

    // ---------------------------------------------------------------------
    // One-shot snapshot reads (for SmsLookupWorker)
    // ---------------------------------------------------------------------

    /**
     * Reads every value the SMS pipeline needs in a single pass.
     *
     * Taking one snapshot rather than collecting several flows guarantees the worker
     * evaluates one message against a consistent set of settings, even if the user changes a
     * toggle mid-processing.
     *
     * @return An immutable [SettingsSnapshot] of the current values.
     */
    suspend fun snapshot(): SettingsSnapshot {
        val prefs = preferences.first()
        return SettingsSnapshot(
            firstRunComplete = prefs[KEY_FIRST_RUN_COMPLETE] ?: DEFAULT_FIRST_RUN_COMPLETE,
            autoReplyEnabled = prefs[KEY_AUTO_REPLY_ENABLED] ?: DEFAULT_AUTO_REPLY_ENABLED,
            useHubSpot = prefs[KEY_USE_HUBSPOT] ?: DEFAULT_USE_HUBSPOT,
            beepOnOptOut = prefs[KEY_BEEP_ON_OPT_OUT] ?: DEFAULT_BEEP_ON_OPT_OUT,
            soundFileUri = prefs[KEY_SOUND_FILE_URI]?.takeIf(String::isNotBlank),
            optOutNotificationEnabled = prefs[KEY_OPT_OUT_NOTIFICATION_ENABLED]
                ?: DEFAULT_OPT_OUT_NOTIFICATION_ENABLED,
        )
    }

    /**
     * Persists a preference key-value pair asynchronously into DataStore.
     *
     * @param key The preference key to write.
     * @param value The value to store.
     */
    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        runCatching {
            dataStore.edit { prefs -> prefs[key] = value }
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist preference ${key.name}", error)
        }
    }

    companion object {
        /** Logging tag for this class. */
        private const val TAG = "SettingsDataStore"

        /** File name of the preferences store, without extension. */
        const val STORE_NAME: String = "smsfilter_settings"

        /** Auto-reply is on by default; the wizard's final step discloses this to the user. */
        const val DEFAULT_AUTO_REPLY_ENABLED: Boolean = true

        /** HubSpot is off by default and never blocks first-run completion. */
        const val DEFAULT_USE_HUBSPOT: Boolean = false

        /** Sound on reply is on by default. */
        const val DEFAULT_BEEP_ON_OPT_OUT: Boolean = true

        /** Detection notifications are on by default. */
        const val DEFAULT_OPT_OUT_NOTIFICATION_ENABLED: Boolean = true

        /** Default UI language: US English. */
        const val DEFAULT_APP_LANGUAGE: String = "en"

        /** The wizard has not run on a fresh install. */
        const val DEFAULT_FIRST_RUN_COMPLETE: Boolean = false

        /** The one-time HubSpot prompt has not been shown on a fresh install. */
        const val DEFAULT_HUBSPOT_PROMPT_SHOWN: Boolean = false

        /** Key for auto-reply enabled flag. */
        private val KEY_AUTO_REPLY_ENABLED = booleanPreferencesKey("auto_reply_enabled")

        /** Key for HubSpot enabled flag. */
        private val KEY_USE_HUBSPOT = booleanPreferencesKey("use_hubspot")

        /** Key for beep on opt-out flag. */
        private val KEY_BEEP_ON_OPT_OUT = booleanPreferencesKey("beep_on_opt_out")

        /** Key for custom sound file URI. */
        private val KEY_SOUND_FILE_URI = stringPreferencesKey("sound_file_uri")

        /** Key for opt-out notification enabled flag. */
        private val KEY_OPT_OUT_NOTIFICATION_ENABLED =
            booleanPreferencesKey("opt_out_notification_enabled")

        /** Key for app language preference. */
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")

        /** Key for first run completed flag. */
        private val KEY_FIRST_RUN_COMPLETE = booleanPreferencesKey("first_run_complete")

        /** Key for HubSpot prompt shown flag. */
        private val KEY_HUBSPOT_PROMPT_SHOWN = booleanPreferencesKey("hubspot_prompt_shown")

        /** Key for persisted Google Contacts status. */
        private val KEY_GOOGLE_CONTACTS_STATUS = stringPreferencesKey("google_contacts_status")

        /** Key for persisted HubSpot status. */
        private val KEY_HUBSPOT_STATUS = stringPreferencesKey("hubspot_status")
    }
}

/**
 * An immutable snapshot of the settings the SMS pipeline consults for one message.
 *
 * @property firstRunComplete Whether onboarding finished; `false` means drop the message.
 * @property autoReplyEnabled Whether replies may be sent at all.
 * @property useHubSpot Whether to consult HubSpot during the unknown-sender check.
 * @property beepOnOptOut Whether to play a sound after a reply is sent.
 * @property soundFileUri Configured sound URI, or `null` for the system notification sound.
 * @property optOutNotificationEnabled Whether to post the detection notification.
 */
data class SettingsSnapshot(
    val firstRunComplete: Boolean,
    val autoReplyEnabled: Boolean,
    val useHubSpot: Boolean,
    val beepOnOptOut: Boolean,
    val soundFileUri: String?,
    val optOutNotificationEnabled: Boolean,
)
