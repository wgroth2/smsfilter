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

import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a "Connect & Test" attempt failed, so the UI can explain the specific problem. */
enum class HubSpotConnectError {
    /** The token was rejected as invalid or revoked. */
    INVALID_TOKEN,

    /** The token is valid but lacks the crm.objects.contacts.read scope. */
    MISSING_SCOPE,

    /** HubSpot could not be reached. */
    NETWORK,
}

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
 * The stop list and pattern list are exposed as their own flows straight from Room rather than being
 * copied into [SettingsUiState], so an edit shows up without this class having to mirror the
 * database.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val secureTokenStore: SecureTokenStore,
    private val contactRepository: ContactRepository,
    private val hubSpotRepository: HubSpotRepository,
    private val healthEvaluator: ConnectionHealthEvaluator,
    private val stopListDao: StopListDao,
    private val optOutPatternDao: OptOutPatternDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())

    /** State for the Settings UI. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Live stop list, observed from Room. */
    val stopList: StateFlow<List<StopListEntity>> = stopListDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Live opt-out patterns, observed from Room. */
    val patterns: StateFlow<List<OptOutPatternEntity>> = optOutPatternDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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
     * Recomputes both health indicators and notification access state.
     *
     * Called on resume as well as on settings changes, because contacts access can be revoked from
     * system settings while the app is backgrounded and the indicator must reflect that immediately.
     */
    fun refreshHealth() {
        val hasToken = secureTokenStore.hasAccessToken()
        val hasContacts = contactRepository.hasReadContactsPermission()
        val isNotificationAccessGranted = isNotificationListenerEnabled(context)
        _uiState.update { state ->
            state.copy(
                hasHubSpotToken = hasToken,
                isNotificationAccessGranted = isNotificationAccessGranted,
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
     * Checks whether Notification Access is granted to this app.
     *
     * @param context Context used to query enabled notification listeners.
     * @return `true` if Notification Access is active, `false` otherwise.
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        if (enabledPackages.contains(context.packageName)) return true
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return raw.contains(context.packageName)
    }

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
     * The token must be written before testing, because the repository reads it from secure storage
     * and accepts no token parameter. On failure it is cleared again, so a mistyped token never
     * silently becomes the app's stored credential — and the status is reset to
     * [ConnectionStatus.SETUP_INCOMPLETE], because a 401 will have persisted an auth error that is
     * stale the instant the token is gone.
     *
     * @param token The pasted token.
     */
    fun connectHubSpot(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, connectError = null) }
            secureTokenStore.saveAccessToken(token.trim())

            when (val outcome = hubSpotRepository.testConnection()) {
                is ContactLookupOutcome.Failed -> {
                    secureTokenStore.clearAccessToken()
                    settingsDataStore.setHubSpotStatus(ConnectionStatus.SETUP_INCOMPLETE)
                    _uiState.update {
                        it.copy(isConnecting = false, connectError = classify(outcome.reason))
                    }
                }

                else -> {
                    settingsDataStore.setHubSpotStatus(ConnectionStatus.CONNECTED)
                    _uiState.update { it.copy(isConnecting = false, connectError = null) }
                }
            }
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
                is ContactLookupOutcome.Failed -> HubSpotCheck.Failed(classify(outcome.reason))
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
     * Adds a stop-list keyword.
     *
     * Blank input is ignored: an empty keyword is a substring of every message and would silence
     * the app entirely.
     *
     * @param keyword The keyword to add.
     */
    fun addStopListKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { stopListDao.insert(StopListEntity(keyword = trimmed)) }
    }

    /** @param entity The stop-list row to remove. */
    fun deleteStopListKeyword(entity: StopListEntity) {
        viewModelScope.launch { stopListDao.delete(entity) }
    }

    /**
     * Adds an opt-out pattern.
     *
     * @param pattern The pattern text; blank input is ignored.
     * @param replyType Which keyword to reply with.
     * @param matchMode How the pattern is evaluated.
     */
    fun addPattern(pattern: String, replyType: ReplyType, matchMode: MatchMode) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            optOutPatternDao.insert(
                OptOutPatternEntity(pattern = trimmed, replyType = replyType, matchMode = matchMode),
            )
        }
    }

    /**
     * Updates an existing opt-out pattern.
     *
     * Blank input is ignored.
     *
     * @param id The row ID of the pattern to update.
     * @param pattern The updated pattern text; blank input is ignored.
     * @param replyType Which keyword to reply with.
     * @param matchMode How the pattern is evaluated.
     */
    fun updatePattern(id: Long, pattern: String, replyType: ReplyType, matchMode: MatchMode) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            optOutPatternDao.update(
                OptOutPatternEntity(
                    id = id,
                    pattern = trimmed,
                    replyType = replyType,
                    matchMode = matchMode,
                ),
            )
        }
    }

    /** @param entity The pattern row to remove. */
    fun deletePattern(entity: OptOutPatternEntity) {
        viewModelScope.launch { optOutPatternDao.delete(entity) }
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

    /**
     * Maps an error reason string returned by [HubSpotRepository] to a categorized [HubSpotConnectError].
     *
     * @param reason The internal failure reason string.
     * @return The classified [HubSpotConnectError] suitable for UI display.
     */
    private fun classify(reason: String): HubSpotConnectError = when {
        reason == HubSpotRepositoryImpl.REASON_UNAUTHORIZED -> HubSpotConnectError.INVALID_TOKEN
        reason == HubSpotRepositoryImpl.REASON_NO_TOKEN -> HubSpotConnectError.INVALID_TOKEN
        reason.contains(FORBIDDEN_MARKER) -> HubSpotConnectError.MISSING_SCOPE
        else -> HubSpotConnectError.NETWORK
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Substring of the repository's `http_403` reason, meaning a missing scope. */
        const val FORBIDDEN_MARKER = "403"
    }
}
