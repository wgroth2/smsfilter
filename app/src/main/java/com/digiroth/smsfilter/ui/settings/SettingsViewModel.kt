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

package com.digiroth.smsfilter.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import com.digiroth.smsfilter.ui.util.isNotificationListenerEnabled
import com.digiroth.smsfilter.domain.hubspot.ConnectHubSpotResult
import com.digiroth.smsfilter.domain.hubspot.ConnectHubSpotUseCase
import com.digiroth.smsfilter.domain.hubspot.HubSpotConnectError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of a Google Contacts diagnostic. */
sealed interface ContactsCheck {
    /** Not run in this session. */
    data object NotRun : ContactsCheck

    /** In progress. */
    data object Running : ContactsCheck

    /**
     * Contacts were readable.
     *
     * @property count How many contacts hold a phone number.
     */
    data class Accessible(val count: Int) : ContactsCheck

    /** Contacts were not readable, almost always because the permission is denied. */
    data object Denied : ContactsCheck
}

/** Result of a HubSpot diagnostic. */
sealed interface HubSpotCheck {
    /** Not run in this session. */
    data object NotRun : HubSpotCheck

    /** In progress. */
    data object Running : HubSpotCheck

    /** The call succeeded. */
    data object Healthy : HubSpotCheck

    /**
     * The call failed.
     *
     * @property error The specific cause, so the message can be actionable.
     */
    data class Failed(val error: HubSpotConnectError) : HubSpotCheck
}

/**
 * Immutable state for the Settings screen.
 *
 * @property autoReplyEnabled Master auto-reply switch.
 * @property beepOnOptOut Whether to play a sound after a reply is sent.
 * @property soundFileUri Configured sound, or `null` for the system default.
 * @property optOutNotificationEnabled Whether detections raise a notification.
 * @property appLanguage Recorded language preference, an ISO 639-1 code.
 * @property useHubSpot Whether HubSpot lookups are enabled.
 * @property hasHubSpotToken Whether a token is stored.
 * @property isNotificationAccessGranted Whether Notification Access is enabled for RCS messages.
 * @property messageIntakeHealth Evaluated intake health indicator for SMS, MMS, and RCS.
 * @property hubSpotHealth Derived HubSpot indicator.
 * @property googleContactsHealth Derived Google Contacts indicator.
 * @property contactsCheck Result of the Google Contacts diagnostic.
 * @property hubSpotCheck Result of the HubSpot diagnostic.
 * @property isConnecting Whether a Connect & Test is in flight.
 * @property connectError Inline error under the token field, if the last attempt failed.
 * @property showHubSpotPrompt Whether the one-time post-onboarding dialog should be shown.
 */
data class SettingsUiState(
    val autoReplyEnabled: Boolean = true,
    val beepOnOptOut: Boolean = true,
    val soundFileUri: String? = null,
    val optOutNotificationEnabled: Boolean = true,
    val appLanguage: String = "en",
    val useHubSpot: Boolean = false,
    val hasHubSpotToken: Boolean = false,
    val isNotificationAccessGranted: Boolean = false,
    val messageIntakeHealth: MessageIntakeHealth = MessageIntakeHealth.FULL,
    val hubSpotHealth: HubSpotHealth = HubSpotHealth.OFF,
    val googleContactsHealth: GoogleContactsHealth = GoogleContactsHealth.PERMISSION_REQUIRED,
    val contactsCheck: ContactsCheck = ContactsCheck.NotRun,
    val hubSpotCheck: HubSpotCheck = HubSpotCheck.NotRun,
    val isConnecting: Boolean = false,
    val connectError: HubSpotConnectError? = null,
    val showHubSpotPrompt: Boolean = false,
)

/**
 * State holder for the Settings screen.
 *
 * For official Android documentation on architecture and ViewModel StateFlow management, see:
 * - Architecture Guide: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Health indicators are derived through [ConnectionHealthEvaluator] rather than computed inline, so
 * the four-state HubSpot rule — in which two of the four states must never render as errors — lives
 * in one tested place.
 *
 * The stop list and pattern editors moved to the Rules destination, each with its own ViewModel;
 * what remains here is the preferences the user sets, the HubSpot credential flow, and the
 * diagnostics that exercise both integrations.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val secureTokenStore: SecureTokenStore,
    private val contactRepository: ContactRepository,
    private val hubSpotRepository: HubSpotRepository,
    private val connectHubSpotUseCase: ConnectHubSpotUseCase,
    private val healthEvaluator: ConnectionHealthEvaluator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())

    /** State for the Settings UI. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Latest persisted HubSpot status, kept here so health can be derived synchronously. */
    private var lastHubSpotStatus: ConnectionStatus = ConnectionStatus.UNKNOWN

    init {
        collect(settingsDataStore.autoReplyEnabled) { v -> _uiState.update { it.copy(autoReplyEnabled = v) } }
        collect(settingsDataStore.beepOnOptOut) { v -> _uiState.update { it.copy(beepOnOptOut = v) } }
        collect(settingsDataStore.soundFileUri) { v -> _uiState.update { it.copy(soundFileUri = v) } }
        collect(settingsDataStore.appLanguage) { v -> _uiState.update { it.copy(appLanguage = v) } }
        collect(settingsDataStore.optOutNotificationEnabled) { v ->
            _uiState.update { it.copy(optOutNotificationEnabled = v) }
        }
        collect(settingsDataStore.hubSpotPromptShown) { shown ->
            _uiState.update { it.copy(showHubSpotPrompt = !shown) }
        }
        collect(settingsDataStore.useHubSpot) { v ->
            _uiState.update { it.copy(useHubSpot = v) }
            refreshHealth()
        }
        collect(settingsDataStore.hubSpotStatus) { status ->
            lastHubSpotStatus = status
            refreshHealth()
        }
        refreshHealth()
    }

    /**
     * Launches a coroutine in [viewModelScope] to collect emissions from the specified [flow].
     *
     * @param flow The upstream flow to observe.
     * @param action Suspending lambda to execute for each emitted value.
     */
    private fun <T> collect(flow: kotlinx.coroutines.flow.Flow<T>, action: suspend (T) -> Unit) {
        viewModelScope.launch { flow.collect(action) }
    }

    /**
     * Recomputes health indicators, message intake health, and notification access state.
     *
     * Called on resume as well as on settings changes, because contacts access or notification
     * access can be modified from system settings while the app is backgrounded and the indicators
     * must reflect that immediately.
     */
    fun refreshHealth() {
        val hasToken = secureTokenStore.hasAccessToken()
        val hasContacts = contactRepository.hasReadContactsPermission()
        val isNotificationAccessGranted = isNotificationListenerEnabled(context)
        val hasReceiveSms = hasReceiveSmsPermission(context)
        val messageIntakeHealth = healthEvaluator.evaluateMessageIntake(
            hasReceiveSmsPermission = hasReceiveSms,
            isNotificationAccessGranted = isNotificationAccessGranted,
        )
        _uiState.update { state ->
            state.copy(
                hasHubSpotToken = hasToken,
                isNotificationAccessGranted = isNotificationAccessGranted,
                messageIntakeHealth = messageIntakeHealth,
                googleContactsHealth = healthEvaluator.evaluateGoogleContacts(hasContacts),
                hubSpotHealth = healthEvaluator.evaluateHubSpot(
                    isEnabled = state.useHubSpot,
                    hasToken = hasToken,
                    lastStatus = lastHubSpotStatus,
                ),
            )
        }
    }

    /**
     * Checks whether the RECEIVE_SMS runtime permission is currently held.
     *
     * @param context Context used to check permission.
     * @return `true` if RECEIVE_SMS is granted, `false` otherwise.
     */
    fun hasReceiveSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** @param enabled New auto-reply master switch value. */
    fun setAutoReplyEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoReplyEnabled(enabled) }
    }

    /** @param enabled New beep-on-opt-out value. */
    fun setBeepOnOptOut(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setBeepOnOptOut(enabled) }
    }

    /** @param uri New sound URI, or `null` for the system default. */
    fun setSoundFileUri(uri: String?) {
        viewModelScope.launch { settingsDataStore.setSoundFileUri(uri) }
    }

    /** @param enabled New detection-notification value. */
    fun setOptOutNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setOptOutNotificationEnabled(enabled) }
    }

    /**
     * Records the chosen language.
     *
     * This is a record of the preference only. The live locale is owned by `AppCompatDelegate`,
     * which the UI applies and reads back — on API 33+ the user can change per-app language from
     * system settings, and this value would not see that.
     *
     * @param languageCode An ISO 639-1 code.
     */
    fun setAppLanguage(languageCode: String) {
        viewModelScope.launch { settingsDataStore.setAppLanguage(languageCode) }
    }

    /**
     * Toggles HubSpot. Turning it off retains any saved token so re-enabling reconnects without
     * re-entry; only an explicit disconnect forgets it.
     *
     * @param enabled New toggle value.
     */
    fun setUseHubSpot(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setUseHubSpot(enabled)
            if (!enabled) settingsDataStore.setHubSpotStatus(ConnectionStatus.OFF)
        }
    }

    /**
     * Saves and validates a pasted Private App token.
     *
     * The save-then-verify sequence, and the clear-on-failure guarantee that goes with it, live in
     * [ConnectHubSpotUseCase] so the onboarding wizard performs them identically. This method owns
     * only the screen state around the call: the in-flight spinner and the inline error.
     *
     * @param token The pasted token.
     */
    fun connectHubSpot(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, connectError = null) }

            val error = when (val result = connectHubSpotUseCase(token)) {
                is ConnectHubSpotResult.Failure -> result.error
                ConnectHubSpotResult.Success -> null
            }

            _uiState.update { it.copy(isConnecting = false, connectError = error) }
            refreshHealth()
        }
    }

    /** Forgets the stored token and marks HubSpot disconnected. */
    fun disconnectHubSpot() {
        viewModelScope.launch {
            secureTokenStore.clearAccessToken()
            settingsDataStore.setUseHubSpot(false)
            settingsDataStore.setHubSpotStatus(ConnectionStatus.DISCONNECTED)
            _uiState.update { it.copy(hubSpotCheck = HubSpotCheck.NotRun, connectError = null) }
            refreshHealth()
        }
    }

    /** Runs the Google Contacts diagnostic. */
    fun testContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(contactsCheck = ContactsCheck.Running) }
            val count = contactRepository.countContactsWithPhoneNumbers()
            _uiState.update {
                it.copy(
                    contactsCheck = if (count == null) ContactsCheck.Denied else ContactsCheck.Accessible(count),
                )
            }
            refreshHealth()
        }
    }

    /** Runs the HubSpot diagnostic. */
    fun testHubSpot() {
        viewModelScope.launch {
            _uiState.update { it.copy(hubSpotCheck = HubSpotCheck.Running) }
            val result = when (val outcome = hubSpotRepository.testConnection()) {
                is ContactLookupOutcome.Failed -> HubSpotCheck.Failed(connectHubSpotUseCase.classify(outcome.reason))
                else -> HubSpotCheck.Healthy
            }
            _uiState.update { it.copy(hubSpotCheck = result) }
            refreshHealth()
        }
    }

    /** Runs both diagnostics; HubSpot only when it is switched on. */
    fun testAllConnections() {
        testContacts()
        if (_uiState.value.useHubSpot) testHubSpot()
    }

    /**
     * Records that the one-time HubSpot prompt has been shown.
     *
     * Called from every dismissal path — connect, decline, back, and outside tap — so the dialog can
     * appear at most once ever.
     *
     * @param connect Whether the user chose to connect, which also enables the toggle.
     */
    fun onHubSpotPromptDismissed(connect: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setHubSpotPromptShown(true)
            if (connect) settingsDataStore.setUseHubSpot(true)
            _uiState.update { it.copy(showHubSpotPrompt = false) }
            refreshHealth()
        }
    }

    private companion object {
        /** Keeps Room-backed flows alive briefly across configuration changes. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
