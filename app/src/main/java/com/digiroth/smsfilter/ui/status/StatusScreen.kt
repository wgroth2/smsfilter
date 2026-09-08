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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.ui.components.SectionDivider
import com.digiroth.smsfilter.ui.components.SectionTitle
import com.digiroth.smsfilter.ui.settings.ContactsCheck
import com.digiroth.smsfilter.ui.settings.GoogleContactsHealth
import com.digiroth.smsfilter.ui.settings.HubSpotHealth
import com.digiroth.smsfilter.ui.settings.MessageIntakeHealth
import com.digiroth.smsfilter.ui.util.openAppSettings
import com.digiroth.smsfilter.ui.util.openNotificationListenerSettings
import com.digiroth.smsfilter.util.BuildInfo
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width

/**
 * The health dashboard, and the app's home destination.
 *
 * For official Android documentation on architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * The resume observer is bound to [LocalLifecycleOwner], which under Navigation Compose is this
 * destination's `NavBackStackEntry` rather than the Activity. That distinction is load-bearing:
 * returning here from Settings resumes the entry but not the Activity, so an Activity-scoped
 * observer would never fire and the indicators would show state from the last cold start.
 *
 * @param onNavigateToSettings Opens the pushed Settings screen.
 * @param viewModel State holder, supplied by Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val observer = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshHealth()
        }
    }
    DisposableEffect(lifecycleOwner, observer) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.status_title)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.action_open_settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.messageIntakeHealth != MessageIntakeHealth.FULL) {
                IncompletePermissionsWarningCard(
                    health = state.messageIntakeHealth,
                    onOpenNotificationListenerSettings = { openNotificationListenerSettings(context) },
                    onOpenAppSettings = { openAppSettings(context) },
                )
                Spacer(Modifier.height(16.dp))
            }

            ConnectionHealthSection(
                googleHealth = state.googleContactsHealth,
                hubSpotHealth = state.hubSpotHealth,
                messageIntakeHealth = state.messageIntakeHealth,
            )
            SectionDivider()

            RecentActivitySection(
                evaluatedToday = state.evaluatedToday,
                lastDetection = state.lastDetection,
            )
            SectionDivider()

            GoogleContactsSection(
                health = state.googleContactsHealth,
                check = state.contactsCheck,
                onTest = viewModel::testContacts,
                onOpenAppSettings = { openAppSettings(context) },
            )
            SectionDivider()

            RcsChatMessagesSection(
                isNotificationAccessGranted = state.isNotificationAccessGranted,
                onOpenNotificationListenerSettings = { openNotificationListenerSettings(context) },
            )
            SectionDivider()

            Text(
                text = BuildInfo.formatBuildTime(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Summarises what the filter has done recently.
 *
 * The evaluated count deliberately includes messages that matched nothing, because the question it
 * answers is "is the filter seeing traffic at all" — a detections-only figure reads zero on a quiet
 * day and is indistinguishable from a receiver that never fired.
 *
 * @param evaluatedToday Messages evaluated since local midnight.
 * @param lastDetection The most recent detection, or `null` if there has never been one.
 */
@Composable
private fun RecentActivitySection(evaluatedToday: Int, lastDetection: DetectionLogEntity?) {
    SectionTitle(stringResource(R.string.status_recent_activity_title))

    Text(
        text = stringResource(R.string.status_evaluated_today, evaluatedToday),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(4.dp))

    val detectionText = lastDetection?.let { entry ->
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.timestamp))
        stringResource(R.string.status_last_detection, timestamp, entry.replyStatus.orEmpty())
    } ?: stringResource(R.string.status_no_detections_yet)

    Text(
        text = detectionText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Displays a prominent warning card when permissions or notification access are incomplete,
 * alerting the user that incoming messages may be missed.
 *
 * @param health The evaluated message intake health.
 * @param onOpenNotificationListenerSettings Launches notification access settings.
 * @param onOpenAppSettings Launches application details settings.
 */
@Composable
private fun IncompletePermissionsWarningCard(
    health: MessageIntakeHealth,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val isSmsDisabled = health == MessageIntakeHealth.DISABLED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSmsDisabled) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (isSmsDisabled) {
                        R.string.settings_intake_warning_title_sms
                    } else {
                        R.string.settings_intake_warning_title_rcs_mms
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = if (isSmsDisabled) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (isSmsDisabled) {
                        R.string.settings_intake_warning_body_sms
                    } else {
                        R.string.settings_intake_warning_body_rcs_mms
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (isSmsDisabled) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = if (isSmsDisabled) onOpenAppSettings else onOpenNotificationListenerSettings,
            ) {
                Text(
                    text = stringResource(
                        if (isSmsDisabled) {
                            R.string.permission_action_open_settings
                        } else {
                            R.string.settings_rcs_action_enable
                        },
                    ),
                )
            }
        }
    }
}

/**
 * Renders the Connection Health Summary section with color-coded status dots for Google Contacts and HubSpot.
 *
 * @param googleHealth The evaluated Google Contacts connection health.
 * @param hubSpotHealth The evaluated HubSpot integration health.
 * @param messageIntakeHealth The evaluated message intake health across SMS, MMS, and RCS.
 */
@Composable
private fun ConnectionHealthSection(
    googleHealth: GoogleContactsHealth,
    hubSpotHealth: HubSpotHealth,
    messageIntakeHealth: MessageIntakeHealth,
) {
    // Session-only dismissal: there is no preference key for this and adding one is out of scope,
    // so the card reappearing after a cold start is the accepted trade-off.
    var privacyCardDismissed by rememberSaveable { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.settings_health_title))

    HealthRow(
        label = stringResource(R.string.health_message_intake),
        color = when (messageIntakeHealth) {
            MessageIntakeHealth.FULL -> HealthColors.GREEN
            MessageIntakeHealth.PARTIAL_SMS_ONLY -> HealthColors.AMBER
            MessageIntakeHealth.DISABLED -> HealthColors.RED
        },
        status = stringResource(
            when (messageIntakeHealth) {
                MessageIntakeHealth.FULL -> R.string.health_intake_full
                MessageIntakeHealth.PARTIAL_SMS_ONLY -> R.string.health_intake_partial
                MessageIntakeHealth.DISABLED -> R.string.health_intake_disabled
            },
        ),
    )
    Spacer(Modifier.height(8.dp))
    HealthRow(
        label = stringResource(R.string.health_google_contacts),
        color = when (googleHealth) {
            GoogleContactsHealth.CONNECTED -> HealthColors.GREEN
            GoogleContactsHealth.PERMISSION_REQUIRED -> HealthColors.RED
        },
        status = stringResource(
            when (googleHealth) {
                GoogleContactsHealth.CONNECTED -> R.string.health_connected
                GoogleContactsHealth.PERMISSION_REQUIRED -> R.string.health_permission_required
            },
        ),
    )
    Spacer(Modifier.height(8.dp))
    HealthRow(
        label = stringResource(R.string.health_hubspot),
        color = when (hubSpotHealth) {
            HubSpotHealth.OFF -> HealthColors.GRAY
            HubSpotHealth.SETUP_INCOMPLETE -> HealthColors.AMBER
            HubSpotHealth.CONNECTED -> HealthColors.GREEN
            HubSpotHealth.ERROR -> HealthColors.RED
        },
        status = stringResource(
            when (hubSpotHealth) {
                HubSpotHealth.OFF -> R.string.health_off
                HubSpotHealth.SETUP_INCOMPLETE -> R.string.health_setup_incomplete
                HubSpotHealth.CONNECTED -> R.string.health_connected
                HubSpotHealth.ERROR -> R.string.health_error
            },
        ),
    )

    if (!privacyCardDismissed) {
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_privacy_card),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { privacyCardDismissed = true }) {
                    Text(stringResource(R.string.common_dismiss))
                }
            }
        }
    }
}

/**
 * Renders a single connection health indicator row with a colored status dot, service label, and status text.
 *
 * @param label The name of the service or integration.
 * @param color The semantic color representing the health state.
 * @param status The human-readable status description.
 */
@Composable
private fun HealthRow(label: String, color: Color, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(12.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Renders the Google Contacts settings section, including permission status, test connection button,
 * and a shortcut to system App Settings when permission is missing.
 *
 * @param health The current Google Contacts health state.
 * @param check The active or most recent Google Contacts check result.
 * @param onTest Callback to trigger a Google Contacts lookup diagnostic test.
 * @param onOpenAppSettings Callback to open system application settings.
 */
@Composable
private fun GoogleContactsSection(
    health: GoogleContactsHealth,
    check: ContactsCheck,
    onTest: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_google_title))
    Text(
        text = stringResource(
            if (health == GoogleContactsHealth.CONNECTED) {
                R.string.settings_google_permission_granted
            } else {
                R.string.settings_google_permission_denied
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (health == GoogleContactsHealth.PERMISSION_REQUIRED) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenAppSettings) {
            Text(stringResource(R.string.permission_action_open_settings))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onTest) { Text(stringResource(R.string.common_test_connection)) }
    Spacer(Modifier.height(8.dp))
    ContactsCheckText(check)
}

/**
 * Renders the status text resulting from a Google Contacts diagnostic test.
 *
 * @param check The current Google Contacts check result.
 */
@Composable
private fun ContactsCheckText(check: ContactsCheck) {
    when (check) {
        ContactsCheck.NotRun -> Unit
        ContactsCheck.Running -> Text(
            stringResource(R.string.connection_contacts_checking),
            style = MaterialTheme.typography.bodySmall,
        )
        is ContactsCheck.Accessible -> Text(
            stringResource(R.string.connection_contacts_accessible, check.count),
            style = MaterialTheme.typography.bodySmall,
        )
        ContactsCheck.Denied -> Text(
            stringResource(R.string.connection_contacts_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Renders the RCS and Chat Messages notification listener status and configuration section.
 *
 * @param isNotificationAccessGranted Whether system Notification Access is granted to the app.
 * @param onOpenNotificationListenerSettings Callback to open the system Notification Access settings screen.
 */
@Composable
private fun RcsChatMessagesSection(
    isNotificationAccessGranted: Boolean,
    onOpenNotificationListenerSettings: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_rcs_title))
    Text(
        text = stringResource(R.string.settings_rcs_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(
            if (isNotificationAccessGranted) {
                R.string.settings_rcs_status_enabled
            } else {
                R.string.settings_rcs_status_disabled
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!isNotificationAccessGranted) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenNotificationListenerSettings) {
            Text(stringResource(R.string.settings_rcs_action_enable))
        }
    }
}

/** Indicator colours, fixed rather than themed so the semantic meaning is unambiguous. */
private object HealthColors {
    /** Color indicating healthy and connected status (Green). */
    val GREEN = Color(0xFF2E7D32)

    /** Color indicating setup incomplete or action needed (Amber). */
    val AMBER = Color(0xFFF9A825)

    /** Color indicating error or connection failure (Red). */
    val RED = Color(0xFFC62828)

    /** Color indicating disabled or turned off status (Gray). */
    val GRAY = Color(0xFF9E9E9E)
}
