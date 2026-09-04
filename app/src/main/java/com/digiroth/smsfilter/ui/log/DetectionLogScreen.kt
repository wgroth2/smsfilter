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

package com.digiroth.smsfilter.ui.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.db.entity.MessageSource
import java.text.DateFormat
import java.util.Date

/** Logging tag for detection log UI actions. */
private const val TAG: String = "DetectionLogScreen"

/**
 * The activity and detection log.
 *
 * For official Android documentation on architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Displays chronologically ordered log entries from [DetectionLogEntity]. Each entry shows
 * the timestamp, message source designator badge, optional sender address chip (which can be tapped
 * to open the messaging app), event-specific outcome/reason, and the message preview.
 *
 * @param onNavigateBack Returns to Settings.
 * @param viewModel State holder, supplied by Hilt.
 */
@Composable
fun DetectionLogScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: DetectionLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = stringResource(R.string.detection_log_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.setFilter(option) },
                        label = { Text(stringResource(filterLabel(option))) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { entry -> LogRow(entry) }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.log_back))
                }
                OutlinedButton(onClick = viewModel::clearLog, enabled = entries.isNotEmpty()) {
                    Text(stringResource(R.string.log_clear))
                }
            }
        }
    }
}

/**
 * Maps a log filter enum option to its corresponding string resource ID.
 *
 * @param filter The selected log filter.
 * @return String resource ID for the filter chip label.
 */
private fun filterLabel(filter: LogFilter): Int = when (filter) {
    LogFilter.ALL -> R.string.log_filter_all
    LogFilter.DETECTIONS -> R.string.log_filter_detections
    LogFilter.IGNORED -> R.string.log_filter_ignored
    LogFilter.NO_MATCH -> R.string.log_filter_no_match
}

/**
 * Opens the device's default messaging app targeted at the specified sender address.
 *
 * @param context Android context used to launch the intent activity.
 * @param senderAddress The recipient phone number or short code.
 */
fun openConversation(context: Context, senderAddress: String): Unit {
    runCatching {
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${Uri.encode(senderAddress)}"),
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }.onFailure { error ->
        Log.e(TAG, "Failed to launch messaging app: ${error.message}", error)
    }
}

/**
 * Renders a pill badge indicating the origin message source ([MessageSource.SMS], [MessageSource.RCS],
 * or [MessageSource.MMS]).
 *
 * @param source The messaging protocol to display.
 * @param modifier Optional layout modifier.
 */
@Composable
private fun MessageSourceBadge(
    source: MessageSource,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor) = when (source) {
        MessageSource.RCS -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        MessageSource.SMS -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        MessageSource.MMS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = source.name,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Renders a single detection log row card with timestamp, message source badge, sender chip,
 * matched pattern or ignore reason, and message preview.
 *
 * @param entry The log entity to display.
 */
@Composable
private fun LogRow(entry: DetectionLogEntity) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MessageSourceBadge(source = entry.messageSource)
                }
                entry.senderAddress?.takeIf(String::isNotBlank)?.let { sender ->
                    SuggestionChip(
                        onClick = { openConversation(context, sender) },
                        label = {
                            Text(
                                text = sender,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            when (entry.eventType) {
                LogEventType.DETECTION -> {
                    entry.matchedPattern?.let { pattern ->
                        Text(pattern, style = MaterialTheme.typography.titleSmall)
                    }
                    entry.replyStatus?.let { status ->
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }

                LogEventType.IGNORED -> {
                    entry.ignoreReason?.let { reason ->
                        val stopListRegex = Regex("""Ignored: Matched Stop List word '(.+)'""")
                        val match = stopListRegex.matchEntire(reason)
                        if (match != null) {
                            val matchedWord = match.groupValues[1]
                            Text(
                                text = stringResource(R.string.log_ignored_stop_list_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.log_ignored_stop_list_detail, matchedWord),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else when (reason) {
                            "Ignored: Known Google Contact" -> {
                                Text(reason, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.log_ignored_contact_google),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            "Ignored: Known HubSpot Contact" -> {
                                Text(reason, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.log_ignored_contact_hubspot),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            "Ignored: Known contact (cached)" -> {
                                Text(reason, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(R.string.log_ignored_contact_cached),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
                                Text(reason, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }

                // An unmatched row carries no pattern, reply status or ignore reason, so its
                // heading has to come from a string resource rather than from the row itself —
                // otherwise the card would render with a blank status area above the preview.
                LogEventType.NO_MATCH -> {
                    Text(
                        text = stringResource(R.string.log_no_match_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.messagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Formats a log timestamp using the device's locale and time zone.
 *
 * @param epochMillis Event time.
 * @return A localized date and time string.
 */
private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
