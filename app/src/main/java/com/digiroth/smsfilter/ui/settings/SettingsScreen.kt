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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.digiroth.smsfilter.domain.hubspot.HubSpotConnectError
import com.digiroth.smsfilter.util.BuildInfo
import android.provider.Settings as AndroidSettings
import com.digiroth.smsfilter.ui.components.SectionDivider
import com.digiroth.smsfilter.ui.components.SectionTitle
import com.digiroth.smsfilter.ui.util.openUrl

/**
 * Application preferences, the HubSpot integration, and connection diagnostics.
 *
 * Pushed from the Status dashboard rather than being a peer destination, which is the platform
 * convention for settings. Health indicators and the rule editors that once shared this screen
 * now live on Status and Rules respectively; what remains is the things the user changes rather
 * than the things the user checks.
 *
 * For official Android documentation on architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 *
 * @param onNavigateBack Returns to the Status dashboard this screen was pushed from.
 * @param viewModel State holder, supplied by Hilt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
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
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
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
            HubSpotSection(
                state = state,
                onToggle = viewModel::setUseHubSpot,
                onConnect = viewModel::connectHubSpot,
                onDisconnect = viewModel::disconnectHubSpot,
                onTest = viewModel::testHubSpot,
                onOpenHelp = { openUrl(context, HUBSPOT_PRIVATE_APPS_URL) },
            )
            SectionDivider()
            DiagnosticsSection(
                contactsCheck = state.contactsCheck,
                hubSpotCheck = state.hubSpotCheck,
                onTestAll = viewModel::testAllConnections,
            )
            SectionDivider()




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

/**
 * Renders the HubSpot CRM integration configuration section.
 *
 * @param state Current UI state of settings.
 * @param onToggle Callback to toggle the HubSpot integration enabled switch.
 * @param onConnect Callback to connect with a Private App token string.
 * @param onDisconnect Callback to disconnect and clear stored token.
 * @param onTest Callback to execute a connection test against HubSpot API.
 * @param onOpenHelp Callback to open HubSpot Private Apps documentation URL.
 */
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

/**
 * Renders the result message for a HubSpot API connectivity diagnostic test.
 *
 * @param check The current HubSpot diagnostic check result.
 */
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

/**
 * Resolves the user-facing error message string resource for a HubSpot connection failure.
 *
 * @param error The specific connection error reason.
 * @return String resource ID of the error message.
 */
private fun connectErrorMessage(error: HubSpotConnectError): Int = when (error) {
    HubSpotConnectError.INVALID_TOKEN -> R.string.settings_hubspot_error_invalid_token
    HubSpotConnectError.MISSING_SCOPE -> R.string.settings_hubspot_error_missing_scope
    HubSpotConnectError.NETWORK -> R.string.settings_hubspot_error_network
}

/**
 * Renders the master Auto-Reply configuration section, disclosing the 24-hour cooldown rule.
 *
 * @param enabled Whether automatic replies are active.
 * @param onToggle Callback invoked when the auto-reply switch state changes.
 */
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

/**
 * Renders the Sound and Language settings section, allowing customization of alert tones and app locale.
 *
 * @param beepEnabled Whether sound alerts are played on successful opt-out replies.
 * @param notificationsEnabled Whether high-priority notifications are posted on detections.
 * @param soundUri The custom sound URI string, or `null` for system default.
 * @param onBeepToggle Callback invoked when the beep toggle state changes.
 * @param onNotificationToggle Callback invoked when the notification toggle state changes.
 * @param onSoundSelected Callback invoked when a new notification sound URI is selected.
 * @param onLanguageSelected Callback invoked when a language locale code is chosen.
 */
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

/**
 * Renders the Diagnostics section with a button to run connectivity checks on all integrations.
 *
 * @param contactsCheck The current Google Contacts check result.
 * @param hubSpotCheck The current HubSpot check result.
 * @param onTestAll Callback invoked to test all configured connection endpoints.
 */
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

/**
 * Renders a row containing a descriptive setting label and an aligned toggle switch.
 *
 * @param label The setting label text.
 * @param checked The boolean state of the switch.
 * @param onCheckedChange Callback invoked when the switch is toggled.
 */
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

/**
 * Renders the one-time post-onboarding dialog prompting the user to optionally connect HubSpot CRM.
 *
 * @param onConnect Callback invoked when user elects to connect HubSpot.
 * @param onDecline Callback invoked when user declines or dismisses the prompt.
 */
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
    /** Color indicating healthy and connected status (Green). */
    val GREEN = Color(0xFF2E7D32)

    /** Color indicating setup incomplete or action needed (Amber). */
    val AMBER = Color(0xFFF9A825)

    /** Color indicating error or connection failure (Red). */
    val RED = Color(0xFFC62828)

    /** Color indicating disabled or turned off status (Gray). */
    val GRAY = Color(0xFF9E9E9E)
}

/** Default ISO 639-1 language code. */
private const val DEFAULT_LANGUAGE = "en"

/** URL to HubSpot Private Apps developer documentation. */
private const val HUBSPOT_PRIVATE_APPS_URL = "https://developers.hubspot.com/docs/api/private-apps"

/** Supported application locales mapped to their display name string resource IDs. */
private val LANGUAGES = listOf(
    "en" to R.string.settings_language_english,
    "es" to R.string.settings_language_spanish,
)

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
