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

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.ui.permissions.PermissionsScreen
import com.digiroth.smsfilter.domain.hubspot.HubSpotConnectError
import com.digiroth.smsfilter.ui.util.openNotificationListenerSettings
import com.digiroth.smsfilter.ui.permissions.readPermissionGrants
import com.digiroth.smsfilter.ui.permissions.readShouldShowRationale
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * The three-step first-run wizard: Welcome, Permissions, Connection Test.
 *
 * For official Android documentation on architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Step state lives in [OnboardingViewModel] rather than in a nav graph. The steps are strictly
 * linear, they share one piece of state (permission facts), and step 2 must gate forward movement —
 * a `NavHost` here would add back-stack behaviour that has to be suppressed rather than used.
 *
 * Permission facts are re-read on every `ON_RESUME`. That is what makes returning from App Settings
 * work without the user tapping anything: a one-shot read at composition would leave the screen
 * showing a stale denial after the permission was granted elsewhere.
 *
 * @param onFinished Called once onboarding is marked complete, to leave the wizard.
 * @param viewModel State holder, supplied by Hilt.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-read permission state whenever the app comes back to the foreground.
    val observer = remember(activity) {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onPermissionFactsRefreshed(
                    granted = readPermissionGrants(context),
                    shouldShowRationale = readShouldShowRationale(activity),
                )
                viewModel.onNotificationAccessRefreshed(context)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, observer) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // On first composition, resume at the first genuinely incomplete step rather than always
    // restarting at Welcome, so a force-stop mid-wizard does not lose the user's progress.
    LaunchedEffect(Unit) {
        viewModel.onPermissionFactsRefreshed(
            granted = readPermissionGrants(context),
            shouldShowRationale = readShouldShowRationale(activity),
        )
        viewModel.onNotificationAccessRefreshed(context)
    }

    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) onFinished()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onGetStarted = viewModel::onGetStarted,
                    onSkipToPermissions = {
                        viewModel.onResumeAtStep(viewModel.resolveResumeStep())
                    },
                    canResume = uiState.canLeavePermissionsStep,
                )

                OnboardingStep.PERMISSIONS -> PermissionsScreen(
                    permissionStates = uiState.permissionStates,
                    canContinue = uiState.canLeavePermissionsStep,
                    isContactsDenied = uiState.isContactsDenied,
                    hasPermanentlyDenied = uiState.hasPermanentlyDeniedPermission,
                    onRequestCompleted = viewModel::onPermissionRequestCompleted,
                    onContinue = viewModel::onPermissionsContinue,
                    onBack = viewModel::onBack,
                )

                OnboardingStep.NOTIFICATION_ACCESS -> NotificationAccessStep(
                    isGranted = uiState.isNotificationAccessGranted,
                    onGrant = { openNotificationListenerSettings(context) },
                    onContinue = viewModel::onNotificationAccessContinue,
                    onBack = viewModel::onBack,
                )

                OnboardingStep.CONNECTION_TEST -> ConnectionTestStep(
                    result = uiState.contactsTest,
                    hubSpotConnecting = uiState.hubSpotConnecting,
                    hubSpotConnected = uiState.hubSpotConnected,
                    hubSpotError = uiState.hubSpotError,
                    onConnectHubSpot = viewModel::onConnectHubSpot,
                    onRunAgain = viewModel::runContactsTest,
                    onDone = viewModel::onDone,
                    onBack = viewModel::onBack,
                )
            }
        }
    }
}

/**
 * Step 1: what the app does.
 *
 * @param onGetStarted Advances to permissions.
 * @param onSkipToPermissions Jumps to the first incomplete step when resuming.
 * @param canResume Whether a later step is already satisfied, making the resume affordance useful.
 */
@Composable
private fun WelcomeStep(
    onGetStarted: () -> Unit,
    onSkipToPermissions: () -> Unit,
    canResume: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(
                R.string.onboarding_step_indicator,
                OnboardingStep.WELCOME.displayNumber,
                OnboardingStep.COUNT,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_get_started))
        }
        if (canResume) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSkipToPermissions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_next))
            }
        }
    }
}

/**
 * Step 3: verify contacts access and disclose the auto-reply default.
 *
 * The Done button stays enabled regardless of the contacts result. Contacts access is explicitly
 * non-blocking: without it every sender is simply treated as unknown, which the app handles.
 *
 * @param result Outcome of the contacts check.
 * @param onRunAgain Re-runs the check.
 * @param onDone Completes onboarding.
 * @param onBack Returns to permissions.
 */
@Composable
private fun ConnectionTestStep(
    result: ContactsTestResult,
    hubSpotConnecting: Boolean,
    hubSpotConnected: Boolean,
    hubSpotError: HubSpotConnectError?,
    onConnectHubSpot: (String) -> Unit,
    onRunAgain: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(
                R.string.onboarding_step_indicator,
                OnboardingStep.CONNECTION_TEST.displayNumber,
                OnboardingStep.COUNT,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_connection_test_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_connection_test_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (result == ContactsTestResult.Running) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.height(0.dp))
                }
                Text(
                    text = when (result) {
                        ContactsTestResult.NotRun,
                        ContactsTestResult.Running,
                        -> stringResource(R.string.connection_contacts_checking)

                        is ContactsTestResult.Accessible ->
                            stringResource(R.string.connection_contacts_accessible, result.contactCount)

                        ContactsTestResult.NotAccessible ->
                            stringResource(R.string.connection_contacts_denied)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRunAgain) {
            Text(stringResource(R.string.onboarding_connection_test_run))
        }

        Spacer(Modifier.height(24.dp))
        // Required disclosure: the user must learn that the app will send SMS on their behalf
        // before they finish setup, not after it starts doing so.
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.onboarding_auto_reply_disclosure),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }


        Spacer(Modifier.height(16.dp))
        OnboardingHubSpotCard(
            isConnecting = hubSpotConnecting,
            isConnected = hubSpotConnected,
            error = hubSpotError,
            onConnect = onConnectHubSpot,
        )

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.common_back))
            }
            // Enabled unconditionally — a denied contacts permission must not trap the user here.
            Button(onClick = onDone) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}

/**
 * Step 3: Notification Access, which MMS and RCS filtering depend on.
 *
 * Deliberately non-blocking. Notification Access is a Special App Access that Android will not
 * grant in-app — the user has to find this app in a system list — and a wizard that cannot be
 * finished without it would strand anyone who declines. Cellular SMS filtering, the app's core
 * function, works without it, and the Status dashboard keeps surfacing the gap afterwards.
 *
 * @param isGranted Whether Notification Access is currently held.
 * @param onGrant Opens the system Notification Access screen.
 * @param onContinue Advances to the final step, granted or not.
 * @param onBack Returns to the permissions step.
 */
@Composable
private fun NotificationAccessStep(
    isGranted: Boolean,
    onGrant: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(
                R.string.onboarding_step_indicator,
                OnboardingStep.NOTIFICATION_ACCESS.displayNumber,
                OnboardingStep.COUNT,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_notification_access_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_notification_access_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_notification_access_why),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Card(
            // secondaryContainer, not tertiaryContainer: this app already uses the tertiary
            // container as the milder of two *alert* levels on the intake warning card, so
            // reusing it here would render a granted permission in warning colours.
            colors = CardDefaults.cardColors(
                containerColor = if (isGranted) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Text(
                text = stringResource(
                    if (isGranted) {
                        R.string.settings_rcs_status_enabled
                    } else {
                        R.string.settings_rcs_status_disabled
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isGranted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.padding(16.dp),
            )
        }

        if (!isGranted) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) {
                Text(stringResource(R.string.settings_rcs_action_enable))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onboarding_notification_access_return_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_notification_access_skip_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.common_back))
            }
            Button(onClick = onContinue) {
                Text(
                    stringResource(
                        if (isGranted) R.string.common_next else R.string.onboarding_skip_for_now,
                    ),
                )
            }
        }
    }
}

/**
 * The optional HubSpot connection offered on the final step.
 *
 * Optional in the strong sense: the token is a HubSpot Private App credential the user very likely
 * does not have to hand during first run, so the wizard must remain finishable without it. Nothing
 * here blocks [OnboardingViewModel.onDone].
 *
 * @param isConnecting Whether a connect attempt is in flight.
 * @param isConnected Whether HubSpot has been connected.
 * @param error Inline error from the last attempt, if any.
 * @param onConnect Attempts to connect with the pasted token.
 */
@Composable
private fun OnboardingHubSpotCard(
    isConnecting: Boolean,
    isConnected: Boolean,
    error: HubSpotConnectError?,
    onConnect: (String) -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.onboarding_hubspot_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_hubspot_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isConnected) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.onboarding_hubspot_connected),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.settings_hubspot_token_label)) },
                    singleLine = true,
                    enabled = !isConnecting,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(onboardingConnectErrorMessage(error)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onConnect(token) }, enabled = !isConnecting && token.isNotBlank()) {
                    Text(
                        stringResource(
                            if (isConnecting) {
                                R.string.settings_test_running
                            } else {
                                R.string.settings_hubspot_connect
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Maps a connect failure to the message shown under the token field.
 *
 * @param error The classified failure.
 * @return String resource id for the message.
 */
private fun onboardingConnectErrorMessage(error: HubSpotConnectError): Int = when (error) {
    HubSpotConnectError.INVALID_TOKEN -> R.string.settings_hubspot_error_invalid_token
    HubSpotConnectError.MISSING_SCOPE -> R.string.settings_hubspot_error_missing_scope
    HubSpotConnectError.NETWORK -> R.string.settings_hubspot_error_network
}
