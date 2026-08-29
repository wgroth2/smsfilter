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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.ui.permissions.PermissionsScreen
import com.digiroth.smsfilter.ui.permissions.readPermissionGrants
import com.digiroth.smsfilter.ui.permissions.readShouldShowRationale

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

                OnboardingStep.CONNECTION_TEST -> ConnectionTestStep(
                    result = uiState.contactsTest,
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
            text = stringResource(R.string.onboarding_step_indicator, 1, 3),
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
            Text(
                text = stringResource(R.string.onboarding_hubspot_not_here),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
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
            text = stringResource(R.string.onboarding_step_indicator, 3, 3),
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

        Spacer(Modifier.height(32.dp))
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
