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

package com.digiroth.smsfilter.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import com.digiroth.smsfilter.ui.permissions.AppPermissions
import com.digiroth.smsfilter.ui.permissions.PermissionState
import com.digiroth.smsfilter.ui.permissions.PermissionStateEvaluator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The wizard's three sequential steps. */
enum class OnboardingStep {
    /** Explains what the app does. */
    WELCOME,

    /** Requests the runtime permissions. */
    PERMISSIONS,

    /** Verifies contacts access and discloses the auto-reply default. */
    CONNECTION_TEST,
    ;

    /** One-based position, for the step indicator. */
    val displayNumber: Int
        get() = ordinal + 1

    companion object {
        /** Total number of steps. */
        val COUNT: Int = entries.size
    }
}

/** Outcome of the step 3 contacts check. */
sealed interface ContactsTestResult {

    /** Not yet run. */
    data object NotRun : ContactsTestResult

    /** In progress. */
    data object Running : ContactsTestResult

    /**
     * Contacts could be queried.
     *
     * @property contactCount How many contacts hold a phone number.
     */
    data class Accessible(val contactCount: Int) : ContactsTestResult

    /** Contacts could not be queried, almost always because the permission was denied. */
    data object NotAccessible : ContactsTestResult
}

/**
 * Immutable state driving the onboarding wizard.
 *
 * @property step The step currently displayed.
 * @property permissionStates Evaluated state per permission.
 * @property contactsTest Result of the step 3 check.
 * @property isFinished Set once onboarding has been marked complete, so navigation can react.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val permissionStates: Map<String, PermissionState> = emptyMap(),
    val contactsTest: ContactsTestResult = ContactsTestResult.NotRun,
    val isFinished: Boolean = false,
) {
    /** Whether every blocking permission is granted, so step 2 may be left. */
    val canLeavePermissionsStep: Boolean
        get() = AppPermissions.blocking().all { permission ->
            permissionStates[permission] == PermissionState.Granted
        }

    /** Whether any permission needs App Settings rather than another prompt. */
    val hasPermanentlyDeniedPermission: Boolean
        get() = permissionStates.values.any { it == PermissionState.DeniedPermanently }

    /** Whether contacts access was denied, which warrants a warning but never blocks. */
    val isContactsDenied: Boolean
        get() = permissionStates[AppPermissions.READ_CONTACTS].let {
            it == PermissionState.DeniedCanRetry || it == PermissionState.DeniedPermanently
        }
}

/**
 * State holder for the onboarding wizard.
 *
 * Permission facts arrive from the composable rather than being read here: both
 * `checkSelfPermission` and `shouldShowRequestPermissionRationale` need an `Activity`, which a
 * ViewModel must not retain. The composable supplies the raw booleans on every resume and this class
 * turns them into [PermissionState]s via [PermissionStateEvaluator].
 *
 * The set of already-requested permissions is held in memory only. If the process is killed
 * mid-wizard the set is lost and a denied permission reads as [PermissionState.NotRequested] again —
 * which shows a request button instead of the App Settings hatch. That is the safe direction to fail:
 * an unnecessary prompt is recoverable, whereas wrongly telling the user they are blocked is not.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val contactRepository: ContactRepository,
    private val permissionStateEvaluator: PermissionStateEvaluator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())

    /** State for the wizard UI. */
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Permissions whose request has completed during this process's lifetime. */
    private val requestedPermissions = mutableSetOf<String>()

    /**
     * Recomputes permission states from freshly read platform facts.
     *
     * Called on first composition and again on every resume, so returning from App Settings is
     * detected without the user tapping anything.
     *
     * @param granted Whether each permission is currently held.
     * @param shouldShowRationale Whether Android would still show a prompt for each permission.
     */
    fun onPermissionFactsRefreshed(
        granted: Map<String, Boolean>,
        shouldShowRationale: Map<String, Boolean>,
    ) {
        val states = AppPermissions.all().associateWith { permission ->
            if (AppPermissions.isNotApplicable(permission)) {
                // Predates this API level, so it can never be granted and must not block anything.
                PermissionState.Granted
            } else {
                permissionStateEvaluator.evaluate(
                    isGranted = granted[permission] == true,
                    hasBeenRequested = requestedPermissions.contains(permission),
                    shouldShowRationale = shouldShowRationale[permission] == true,
                )
            }
        }
        _uiState.update { it.copy(permissionStates = states) }
    }

    /**
     * Records that a permission request round-trip finished.
     *
     * This is what makes a permanent denial distinguishable from a fresh install: the platform's
     * rationale flag is `false` in both cases, and only the app knows a request actually happened.
     *
     * @param results The system's per-permission grant results.
     * @param shouldShowRationale Rationale flags read immediately after the result.
     */
    fun onPermissionRequestCompleted(
        results: Map<String, Boolean>,
        shouldShowRationale: Map<String, Boolean>,
    ) {
        requestedPermissions += results.keys
        onPermissionFactsRefreshed(
            granted = results,
            shouldShowRationale = shouldShowRationale,
        )
    }

    /** Advances from the welcome step. */
    fun onGetStarted() {
        _uiState.update { it.copy(step = OnboardingStep.PERMISSIONS) }
    }

    /**
     * Advances past the permissions step, if the blocking permissions allow it.
     *
     * Runs the contacts check on arrival so step 3 shows a result immediately.
     */
    fun onPermissionsContinue() {
        if (!_uiState.value.canLeavePermissionsStep) return
        _uiState.update { it.copy(step = OnboardingStep.CONNECTION_TEST) }
        runContactsTest()
    }

    /** Returns to the previous step. */
    fun onBack() {
        _uiState.update { state ->
            val previous = when (state.step) {
                OnboardingStep.WELCOME -> OnboardingStep.WELCOME
                OnboardingStep.PERMISSIONS -> OnboardingStep.WELCOME
                OnboardingStep.CONNECTION_TEST -> OnboardingStep.PERMISSIONS
            }
            state.copy(step = previous)
        }
    }

    /**
     * Runs the Google Contacts accessibility check.
     *
     * A `null` count means the permission is missing or the query failed; both surface as
     * [ContactsTestResult.NotAccessible], and neither prevents finishing the wizard.
     */
    fun runContactsTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(contactsTest = ContactsTestResult.Running) }
            val count = contactRepository.countContactsWithPhoneNumbers()
            _uiState.update {
                it.copy(
                    contactsTest = if (count == null) {
                        ContactsTestResult.NotAccessible
                    } else {
                        ContactsTestResult.Accessible(count)
                    },
                )
            }
        }
    }

    /**
     * Marks onboarding complete.
     *
     * This is the only place in the app that sets `firstRunComplete`. The SMS pipeline's onboarding
     * gate reads that flag to decide whether to process an incoming message at all, so writing it
     * anywhere else would let messages be acted on before the user finished setup.
     */
    fun onDone() {
        viewModelScope.launch {
            settingsDataStore.setFirstRunComplete(true)
            _uiState.update { it.copy(isFinished = true) }
        }
    }

    /**
     * Chooses which step to resume on, derived from live permission state rather than a persisted
     * index, so a force-stop restart lands on the first genuinely incomplete step.
     *
     * @return The step to display.
     */
    fun resolveResumeStep(): OnboardingStep = if (_uiState.value.canLeavePermissionsStep) {
        OnboardingStep.CONNECTION_TEST
    } else {
        OnboardingStep.PERMISSIONS
    }

    /**
     * Jumps directly to a step. Used when resuming mid-wizard.
     *
     * @param step The step to show.
     */
    fun onResumeAtStep(step: OnboardingStep) {
        _uiState.update { it.copy(step = step) }
        if (step == OnboardingStep.CONNECTION_TEST &&
            _uiState.value.contactsTest == ContactsTestResult.NotRun
        ) {
            runContactsTest()
        }
    }
}
