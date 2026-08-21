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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.util.BuildInfo
import android.provider.Settings as AndroidSettings

/**
 * The app's main screen: connection health, integrations, detection rules, and preferences.
 *
 * Connection health is re-derived on every `ON_RESUME` because contacts access can be revoked from
 * system settings while the app is backgrounded, and a stale green dot would misrepresent whether
 * the filter is actually working.
 *
 * @param onNavigateToLog Opens the activity and detection log.
 * @param viewModel State holder, supplied by Hilt.
 */
@Composable
fun SettingsScreen(
    onNavigateToLog: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stopList by viewModel.stopList.collectAsStateWithLifecycle()
    val patterns by viewModel.patterns.collectAsStateWithLifecycle()
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ConnectionHealthSection(
                googleHealth = state.googleContactsHealth,
                hubSpotHealth = state.hubSpotHealth,
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

            HubSpotSection(
                state = state,
                onToggle = viewModel::setUseHubSpot,
                onConnect = viewModel::connectHubSpot,
                onDisconnect = viewModel::disconnectHubSpot,
                onTest = viewModel::testHubSpot,
                onOpenHelp = { openUrl(context, HUBSPOT_PRIVATE_APPS_URL) },
            )
            SectionDivider()

            StopListSection(
                keywords = stopList,
                onAdd = viewModel::addStopListKeyword,
                onDelete = viewModel::deleteStopListKeyword,
            )
            SectionDivider()

            PatternsSection(
                patterns = patterns,
                onAdd = viewModel::addPattern,
                onUpdate = viewModel::updatePattern,
                onDelete = viewModel::deletePattern,
            )
            SectionDivider()

            AutoReplySection(
                enabled = state.autoReplyEnabled,
                onToggle = viewModel::setAutoReplyEnabled,
            )
            SectionDivider()

            SoundAndLanguageSection(
                beepEnabled = state.beepOnOptOut,
                notificationsEnabled = state.optOutNotificationEnabled,
                soundUri = state.soundFileUri,
                onBeepToggle = viewModel::setBeepOnOptOut,
                onNotificationToggle = viewModel::setOptOutNotificationEnabled,
                onSoundSelected = viewModel::setSoundFileUri,
                onLanguageSelected = viewModel::setAppLanguage,
            )
            SectionDivider()

            DiagnosticsSection(
                contactsCheck = state.contactsCheck,
                hubSpotCheck = state.hubSpotCheck,
                onTestAll = viewModel::testAllConnections,
            )
            SectionDivider()

            OutlinedButton(onClick = onNavigateToLog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_open_log))
            }
            Spacer(Modifier.height(16.dp))

            Text(
                text = BuildInfo.formatBuildTime(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showHubSpotPrompt) {
        HubSpotPromptDialog(
            onConnect = { viewModel.onHubSpotPromptDismissed(connect = true) },
            onDecline = { viewModel.onHubSpotPromptDismissed(connect = false) },
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ConnectionHealthSection(
    googleHealth: GoogleContactsHealth,
    hubSpotHealth: HubSpotHealth,
) {
    // Session-only dismissal: there is no preference key for this and adding one is out of scope,
    // so the card reappearing after a cold start is the accepted trade-off.
    var privacyCardDismissed by rememberSaveable { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.settings_health_title))

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

@Composable
private fun HubSpotSection(
    state: SettingsUiState,
    onToggle: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    SectionTitle(stringResource(R.string.settings_hubspot_title))
    ToggleRow(
        label = stringResource(R.string.settings_hubspot_toggle),
        checked = state.useHubSpot,
        onCheckedChange = onToggle,
    )

    if (!state.useHubSpot) return

    Spacer(Modifier.height(12.dp))
    if (state.hasHubSpotToken) {
        Text(stringResource(R.string.health_connected), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest) {
                Text(stringResource(R.string.common_test_connection))
            }
            OutlinedButton(onClick = onDisconnect) {
                Text(stringResource(R.string.settings_hubspot_disconnect))
            }
        }
        HubSpotCheckText(state.hubSpotCheck)
    } else {
        // Inline connect card. Nothing is auto-launched: the user pastes a token and taps a button.
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.settings_hubspot_token_label)) },
            singleLine = true,
            visualTransformation = if (tokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { tokenVisible = !tokenVisible }) {
            Text(
                stringResource(
                    if (tokenVisible) {
                        R.string.settings_hubspot_hide_token
                    } else {
                        R.string.settings_hubspot_show_token
                    },
                ),
            )
        }
        TextButton(onClick = onOpenHelp) {
            Text(stringResource(R.string.settings_hubspot_token_help))
        }
        Button(
            onClick = { onConnect(token) },
            enabled = token.isNotBlank() && !state.isConnecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isConnecting) {
                CircularProgressIndicator(Modifier.size(16.dp))
            } else {
                Text(stringResource(R.string.settings_hubspot_connect))
            }
        }
        state.connectError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(connectErrorMessage(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HubSpotCheckText(check: HubSpotCheck) {
    when (check) {
        HubSpotCheck.NotRun -> Unit
        HubSpotCheck.Running -> Text(
            stringResource(R.string.settings_test_running),
            style = MaterialTheme.typography.bodySmall,
        )
        HubSpotCheck.Healthy -> Text(
            stringResource(R.string.settings_hubspot_test_ok),
            style = MaterialTheme.typography.bodySmall,
        )
        is HubSpotCheck.Failed -> Text(
            stringResource(connectErrorMessage(check.error)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun connectErrorMessage(error: HubSpotConnectError): Int = when (error) {
    HubSpotConnectError.INVALID_TOKEN -> R.string.settings_hubspot_error_invalid_token
    HubSpotConnectError.MISSING_SCOPE -> R.string.settings_hubspot_error_missing_scope
    HubSpotConnectError.NETWORK -> R.string.settings_hubspot_error_network
}

@Composable
private fun StopListSection(
    keywords: List<StopListEntity>,
    onAdd: (String) -> Unit,
    onDelete: (StopListEntity) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    SectionTitle(stringResource(R.string.settings_stop_list_title))
    if (keywords.isEmpty()) {
        Text(
            stringResource(R.string.settings_stop_list_empty),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    keywords.forEach { entry ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(entry.keyword, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onDelete(entry) }) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.settings_stop_list_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAdd(input)
                input = ""
            },
            enabled = input.isNotBlank(),
        ) {
            Text(stringResource(R.string.common_add))
        }
    }
}

@Composable
private fun PatternsSection(
    patterns: List<OptOutPatternEntity>,
    onAdd: (String, ReplyType, MatchMode) -> Unit,
    onUpdate: (Long, String, ReplyType, MatchMode) -> Unit,
    onDelete: (OptOutPatternEntity) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var replyType by rememberSaveable { mutableStateOf(ReplyType.STOP) }
    var matchMode by rememberSaveable { mutableStateOf(MatchMode.LAST_LINE_EXACT) }
    var patternToEdit by remember { mutableStateOf<OptOutPatternEntity?>(null) }

    SectionTitle(stringResource(R.string.settings_patterns_title))
    Text(
        stringResource(R.string.settings_patterns_case_note),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    patterns.forEach { pattern ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { patternToEdit = pattern }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pattern.pattern, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(matchModeLabel(pattern.matchMode)) +
                        " · " + pattern.replyType.keyword,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onDelete(pattern) }) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }

    patternToEdit?.let { target ->
        EditPatternDialog(
            pattern = target,
            onDismiss = { patternToEdit = null },
            onSave = onUpdate,
        )
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text(stringResource(R.string.settings_patterns_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Text(
        stringResource(R.string.settings_patterns_reply_type),
        style = MaterialTheme.typography.labelMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReplyType.entries.forEach { type ->
            RadioButton(selected = replyType == type, onClick = { replyType = type })
            Text(type.keyword, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
        }
    }

    Text(
        stringResource(R.string.settings_patterns_match_mode),
        style = MaterialTheme.typography.labelMedium,
    )
    MatchMode.entries.forEach { mode ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = matchMode == mode, onClick = { matchMode = mode })
            Text(stringResource(matchModeLabel(mode)), style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            onAdd(input, replyType, matchMode)
            input = ""
        },
        enabled = input.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.common_add))
    }
}

/**
 * Dialog for editing an existing opt-out pattern's keyword, reply type, and match mode.
 *
 * @param pattern The pattern entity being edited.
 * @param onDismiss Callback invoked when the dialog is cancelled or dismissed.
 * @param onSave Callback invoked with the updated pattern values (id, pattern, replyType, matchMode).
 */
@Composable
private fun EditPatternDialog(
    pattern: OptOutPatternEntity,
    onDismiss: () -> Unit,
    onSave: (Long, String, ReplyType, MatchMode) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(pattern.pattern) }
    var replyType by rememberSaveable { mutableStateOf(pattern.replyType) }
    var matchMode by rememberSaveable { mutableStateOf(pattern.matchMode) }

    val trimmed = text.trim()
    val isChanged = (trimmed != pattern.pattern) || (replyType != pattern.replyType) || (matchMode != pattern.matchMode)
    val isValid = trimmed.isNotEmpty() && isChanged

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_patterns_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.settings_patterns_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    stringResource(R.string.settings_patterns_reply_type),
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReplyType.entries.forEach { type ->
                        RadioButton(selected = replyType == type, onClick = { replyType = type })
                        Text(type.keyword, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.settings_patterns_match_mode),
                    style = MaterialTheme.typography.labelMedium,
                )
                MatchMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = matchMode == mode, onClick = { matchMode = mode })
                        Text(stringResource(matchModeLabel(mode)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(matchModeExplanation(matchMode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(pattern.id, trimmed, replyType, matchMode)
                    onDismiss()
                },
                enabled = isValid,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private fun matchModeExplanation(mode: MatchMode): Int = when (mode) {
    MatchMode.ANYWHERE -> R.string.match_mode_anywhere_desc
    MatchMode.LAST_LINE_EXACT -> R.string.match_mode_last_line_desc
    MatchMode.LAST_LINE_CONTAINS -> R.string.match_mode_last_line_contains_desc
}

private fun matchModeLabel(mode: MatchMode): Int = when (mode) {
    MatchMode.ANYWHERE -> R.string.match_mode_anywhere
    MatchMode.LAST_LINE_EXACT -> R.string.match_mode_last_line
    MatchMode.LAST_LINE_CONTAINS -> R.string.match_mode_last_line_contains
}

@Composable
private fun AutoReplySection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SectionTitle(stringResource(R.string.settings_auto_reply_title))
    ToggleRow(
        label = stringResource(R.string.settings_auto_reply_toggle),
        checked = enabled,
        onCheckedChange = onToggle,
    )
    Text(
        stringResource(R.string.settings_auto_reply_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.settings_auto_reply_cooldown_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SoundAndLanguageSection(
    beepEnabled: Boolean,
    notificationsEnabled: Boolean,
    soundUri: String?,
    onBeepToggle: (Boolean) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onSoundSelected: (String?) -> Unit,
    onLanguageSelected: (String) -> Unit,
) {
    val context = LocalContext.current

    val soundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            // Take a persistable read grant so the sound is still playable after the process
            // restarts; without it the worker's later playback can fail with a SecurityException
            // and the beep silently stops working. Not every picker URI supports a persistable
            // grant, so a failure here is non-fatal.
            uri?.let { picked ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        picked,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            onSoundSelected(uri?.toString())
        }
    }

    SectionTitle(stringResource(R.string.settings_sound_language_title))
    ToggleRow(
        label = stringResource(R.string.settings_beep_toggle),
        checked = beepEnabled,
        onCheckedChange = onBeepToggle,
    )
    ToggleRow(
        label = stringResource(R.string.settings_notification_toggle),
        checked = notificationsEnabled,
        onCheckedChange = onNotificationToggle,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = soundUri ?: stringResource(R.string.settings_sound_default),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, soundUri?.toUri())
            }
            soundPicker.launch(intent)
        },
    ) {
        Text(stringResource(R.string.settings_sound_choose))
    }

    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.settings_language_title),
        style = MaterialTheme.typography.labelMedium,
    )
    // AppCompatDelegate is the single source of truth for the live locale: on API 33+ the user can
    // change the per-app language from system settings, which DataStore would never observe.
    val currentLanguage = AppCompatDelegate.getApplicationLocales()
        .takeIf { !it.isEmpty }
        ?.get(0)
        ?.language
        ?: DEFAULT_LANGUAGE
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGUAGES.forEach { (code, labelRes) ->
            FilterChip(
                selected = currentLanguage == code,
                onClick = {
                    onLanguageSelected(code)
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(
    contactsCheck: ContactsCheck,
    hubSpotCheck: HubSpotCheck,
    onTestAll: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_diagnostics_title))
    Button(onClick = onTestAll, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_test_all))
    }
    Spacer(Modifier.height(8.dp))
    ContactsCheckText(contactsCheck)
    HubSpotCheckText(hubSpotCheck)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HubSpotPromptDialog(onConnect: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        // Dismissing by back press or an outside tap counts as declining, and still records that
        // the prompt was shown — the specification allows it to appear at most once, ever.
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.settings_hubspot_prompt_title)) },
        text = { Text(stringResource(R.string.settings_hubspot_prompt_body)) },
        confirmButton = {
            TextButton(onClick = onConnect) {
                Text(stringResource(R.string.settings_hubspot_prompt_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.settings_hubspot_prompt_decline))
            }
        },
    )
}

/** Indicator colours, fixed rather than themed so the semantic meaning is unambiguous. */
private object HealthColors {
    val GREEN = Color(0xFF2E7D32)
    val AMBER = Color(0xFFF9A825)
    val RED = Color(0xFFC62828)
    val GRAY = Color(0xFF9E9E9E)
}

private const val DEFAULT_LANGUAGE = "en"
private const val HUBSPOT_PRIVATE_APPS_URL = "https://developers.hubspot.com/docs/api/private-apps"

private val LANGUAGES = listOf(
    "en" to R.string.settings_language_english,
    "es" to R.string.settings_language_spanish,
)

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

private fun openNotificationListenerSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
