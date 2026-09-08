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

package com.digiroth.smsfilter.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.ui.components.SectionTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Renders the Opt-Out Patterns configuration section, listing custom and default pattern rules
 * and providing an inline input row to add new patterns.
 *
 * @param patterns The list of active opt-out pattern entities.
 * @param onAdd Callback invoked to add a new pattern entity.
 * @param onUpdate Callback invoked to edit an existing pattern entity.
 * @param onDelete Callback invoked to delete an opt-out pattern entity.
 */
@Composable
fun OptOutPatternsTab(
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

/**
 * Resolves the descriptive explanation string resource ID for a given match mode.
 *
 * @param mode The match mode to explain.
 * @return String resource ID explaining the matching rule behavior.
 */
private fun matchModeExplanation(mode: MatchMode): Int = when (mode) {
    MatchMode.ANYWHERE -> R.string.match_mode_anywhere_desc
    MatchMode.LAST_LINE_EXACT -> R.string.match_mode_last_line_desc
    MatchMode.LAST_LINE_CONTAINS -> R.string.match_mode_last_line_contains_desc
}

/**
 * Resolves the short display name string resource ID for a given match mode.
 *
 * @param mode The match mode to name.
 * @return String resource ID of the match mode label.
 */
private fun matchModeLabel(mode: MatchMode): Int = when (mode) {
    MatchMode.ANYWHERE -> R.string.match_mode_anywhere
    MatchMode.LAST_LINE_EXACT -> R.string.match_mode_last_line
    MatchMode.LAST_LINE_CONTAINS -> R.string.match_mode_last_line_contains
}
