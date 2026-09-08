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

package com.digiroth.smsfilter.ui.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import com.digiroth.smsfilter.ui.util.isNotificationListenerEnabled
import com.digiroth.smsfilter.ui.settings.ConnectionHealthEvaluator
import com.digiroth.smsfilter.ui.settings.ContactsCheck
import com.digiroth.smsfilter.ui.settings.GoogleContactsHealth
import com.digiroth.smsfilter.ui.settings.HubSpotHealth
import com.digiroth.smsfilter.ui.settings.MessageIntakeHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * Immutable state for the Status dashboard.
 *
 * @property messageIntakeHealth Evaluated SMS, MMS and RCS intake indicator.
 * @property googleContactsHealth Derived Google Contacts indicator.
 * @property hubSpotHealth Derived HubSpot indicator.
 * @property isNotificationAccessGranted Whether Notification Access is enabled for MMS and RCS.
 * @property contactsCheck Result of the Google Contacts diagnostic.
 * @property useHubSpot Whether HubSpot lookups are switched on.
 * @property evaluatedToday How many messages were evaluated since local midnight.
 * @property lastDetection The most recent detection, or `null` if there has never been one.
 */
data class StatusUiState(
    val messageIntakeHealth: MessageIntakeHealth = MessageIntakeHealth.FULL,
    val googleContactsHealth: GoogleContactsHealth = GoogleContactsHealth.PERMISSION_REQUIRED,
    val hubSpotHealth: HubSpotHealth = HubSpotHealth.OFF,
    val isNotificationAccessGranted: Boolean = false,
    val contactsCheck: ContactsCheck = ContactsCheck.NotRun,
    val useHubSpot: Boolean = false,
    val evaluatedToday: Int = 0,
    val lastDetection: DetectionLogEntity? = null,
)

/**
 * State holder for the Status dashboard.
 *
 * For official Android documentation on architecture and ViewModel StateFlow management, see:
 * - Architecture Guide: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 *
 * Health indicators are derived through [ConnectionHealthEvaluator] rather than computed inline, so
 * the four-state HubSpot rule — in which two of the four states must never render as errors — lives
 * in one tested place.
 *
 * Inputs arrive two ways, deliberately. Whatever can be observed is: the DataStore preferences, the
 * Room activity queries, and token presence via [SecureTokenStore.hasToken], so a change made on
 * the Settings screen reaches this one without a round trip through the system. Two facts cannot be
 * observed at all — the `RECEIVE_SMS` grant and Notification Access — and are re-read by
 * [refreshHealth] when the screen resumes. That is sufficient for both, because the only way to
 * change either is to leave the app for system settings, which guarantees a resume on return.
 *
 * @property context Application context used to read permission and notification-listener state.
 */
@HiltViewModel
class StatusViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val secureTokenStore: SecureTokenStore,
    private val contactRepository: ContactRepository,
    private val healthEvaluator: ConnectionHealthEvaluator,
    private val detectionLogDao: DetectionLogDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatusUiState())

    /** State for the Status UI. */
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    /** Latest persisted HubSpot status, kept here so health can be derived synchronously. */
    private var lastHubSpotStatus: ConnectionStatus = ConnectionStatus.UNKNOWN

    init {
        collect(settingsDataStore.useHubSpot) { enabled ->
            _uiState.update { it.copy(useHubSpot = enabled) }
            refreshHealth()
        }
        collect(settingsDataStore.hubSpotStatus) { status ->
            lastHubSpotStatus = status
            refreshHealth()
        }
        collect(secureTokenStore.hasToken) { refreshHealth() }
        collect(detectionLogDao.observeCountSince(startOfToday())) { count ->
            _uiState.update { it.copy(evaluatedToday = count) }
        }
        collect(detectionLogDao.observeLatestByType(LogEventType.DETECTION)) { entry ->
            _uiState.update { it.copy(lastDetection = entry) }
        }
        refreshHealth()
    }

    /**
     * Launches a coroutine in [viewModelScope] to collect emissions from the specified [flow].
     *
     * @param flow The upstream flow to observe.
     * @param action Suspending lambda to execute for each emitted value.
     */
    private fun <T> collect(flow: Flow<T>, action: suspend (T) -> Unit) {
        viewModelScope.launch { flow.collect(action) }
    }

    /**
     * Recomputes the indicators that cannot be observed.
     *
     * Must be called on every resume: contacts access and Notification Access can both be revoked
     * from system settings while the app is backgrounded, and a stale green dot would misrepresent
     * whether the filter is actually working.
     */
    fun refreshHealth() {
        val hasToken = secureTokenStore.hasAccessToken()
        val hasContacts = contactRepository.hasReadContactsPermission()
        val isNotificationAccessGranted = isNotificationListenerEnabled(context)
        val messageIntakeHealth = healthEvaluator.evaluateMessageIntake(
            hasReceiveSmsPermission = hasReceiveSmsPermission(context),
            isNotificationAccessGranted = isNotificationAccessGranted,
        )
        _uiState.update { state ->
            state.copy(
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

    /** Runs the Google Contacts diagnostic. */
    fun testContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(contactsCheck = ContactsCheck.Running) }
            val count = contactRepository.countContactsWithPhoneNumbers()
            _uiState.update {
                it.copy(
                    contactsCheck = if (count == null) {
                        ContactsCheck.Denied
                    } else {
                        ContactsCheck.Accessible(count)
                    },
                )
            }
            refreshHealth()
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

    /**
     * Local midnight, as the boundary for "evaluated today".
     *
     * Computed once when the ViewModel is created rather than per emission. The figure is a
     * dashboard summary, not an audit total, and re-deriving it on every Room emission would make
     * the query key change constantly for no user-visible gain. A device left open across midnight
     * therefore keeps yesterday's boundary until the screen is recreated.
     *
     * @return Epoch milliseconds at the start of the current local day.
     */
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
