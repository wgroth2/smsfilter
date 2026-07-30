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

package com.digiroth.smsfilter.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.digiroth.smsfilter.R

/**
 * Reads the current grant state of every app permission.
 *
 * @param context Any context; the application context is sufficient for a permission check.
 * @return Permission name to whether it is currently held. Permissions that predate this API level
 *   report `true`, since they can never be granted and must not block progress.
 */
fun readPermissionGrants(context: Context): Map<String, Boolean> =
    AppPermissions.all().associateWith { permission ->
        AppPermissions.isNotApplicable(permission) ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Reads Android's "would a prompt still be shown" flag for every app permission.
 *
 * Requires an `Activity`; returns all-`false` when one is unavailable, which the state evaluator
 * treats as meaningful only once a request has actually completed.
 *
 * @param activity The hosting activity, or `null`.
 * @return Permission name to the rationale flag.
 */
fun readShouldShowRationale(activity: Activity?): Map<String, Boolean> =
    AppPermissions.all().associateWith { permission ->
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

/**
 * Step 2 of the wizard: request the runtime permissions.
 *
 * Each permission is shown with the reason it is needed, its current state, and the one action that
 * can actually change that state — a request prompt, or App Settings once Android has stopped
 * showing prompts. Offering a request button in the permanently-denied case would produce a control
 * that silently does nothing, which is the specific dead end this screen is designed to avoid.
 *
 * @param permissionStates Evaluated state per permission.
 * @param canContinue Whether every blocking permission is granted.
 * @param isContactsDenied Whether to show the non-blocking contacts warning.
 * @param hasPermanentlyDenied Whether any permission now requires App Settings.
 * @param onRequestCompleted Called with grant results and rationale flags after a request returns.
 * @param onContinue Advances to the next step.
 * @param onBack Returns to the welcome step.
 * @param modifier Layout modifier.
 */
@Composable
fun PermissionsScreen(
    permissionStates: Map<String, PermissionState>,
    canContinue: Boolean,
    isContactsDenied: Boolean,
    hasPermanentlyDenied: Boolean,
    onRequestCompleted: (Map<String, Boolean>, Map<String, Boolean>) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Rationale flags must be read immediately after the result: they are what distinguishes a
        // denial that can be retried from one that now needs App Settings.
        onRequestCompleted(results, readShouldShowRationale(activity))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_step_indicator, 2, 3),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_permissions_body),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_permissions_required_header),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        AppPermissions.blocking().forEach { permission ->
            PermissionRow(permission = permission, state = permissionStates[permission])
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_permissions_optional_header),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        AppPermissions.optional().forEach { permission ->
            PermissionRow(permission = permission, state = permissionStates[permission])
            Spacer(Modifier.height(8.dp))
        }

        if (isContactsDenied) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.permission_contacts_denied_warning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (hasPermanentlyDenied) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.permission_permanently_denied),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openAppSettings(context) }) {
                        Text(stringResource(R.string.permission_action_open_settings))
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { permissionLauncher.launch(AppPermissions.all().toTypedArray()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !canContinue,
        ) {
            Text(stringResource(R.string.permission_request_all))
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.common_back))
            }
            Button(onClick = onContinue, enabled = canContinue) {
                Text(stringResource(R.string.common_next))
            }
        }
    }
}

/**
 * One permission's name, reason, and current state.
 *
 * @param permission The permission name.
 * @param state Its evaluated state, or `null` before the first evaluation.
 */
@Composable
private fun PermissionRow(permission: String, state: PermissionState?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(permissionLabel(permission)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(stateLabel(state)),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        PermissionState.Granted -> MaterialTheme.colorScheme.primary
                        PermissionState.DeniedCanRetry,
                        PermissionState.DeniedPermanently,
                        -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(permissionReason(permission)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun permissionLabel(permission: String): Int = when (permission) {
    AppPermissions.RECEIVE_SMS -> R.string.permission_name_receive_sms
    AppPermissions.SEND_SMS -> R.string.permission_name_send_sms
    AppPermissions.READ_CONTACTS -> R.string.permission_name_read_contacts
    else -> R.string.permission_name_post_notifications
}

private fun permissionReason(permission: String): Int = when (permission) {
    AppPermissions.RECEIVE_SMS -> R.string.permission_reason_receive_sms
    AppPermissions.SEND_SMS -> R.string.permission_reason_send_sms
    AppPermissions.READ_CONTACTS -> R.string.permission_reason_read_contacts
    else -> R.string.permission_reason_post_notifications
}

private fun stateLabel(state: PermissionState?): Int = when (state) {
    PermissionState.Granted -> R.string.permission_status_granted
    PermissionState.DeniedCanRetry, PermissionState.DeniedPermanently -> R.string.permission_status_denied
    else -> R.string.permission_status_not_requested
}

/**
 * Opens this app's entry in system App Settings, the only route left once Android stops showing
 * permission prompts.
 *
 * @param context Context used to start the activity.
 */
private fun openAppSettings(context: Context) {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
