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

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.digiroth.smsfilter.R
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.ui.components.SectionTitle
import com.digiroth.smsfilter.ui.theme.SmsFilterTheme

/**
 * Callbacks the Stop List tab raises, grouped so the composable's signature stays readable.
 *
 * @property onAdd Adds a keyword with its options: (keyword, caseSensitive, wholeWord).
 * @property onClearAddError Clears the add form's error after the user changes the input.
 * @property onStartEdit Opens the edit dialog for an entry.
 * @property onSaveEdit Saves the edit dialog: (keyword, caseSensitive, wholeWord).
 * @property onCancelEdit Closes the edit dialog without saving.
 * @property onClearEditError Clears the edit dialog's error after the user changes a field.
 * @property onDelete Deletes an entry.
 */
data class StopListActions(
    val onAdd: (String, Boolean, Boolean) -> Unit,
    val onClearAddError: () -> Unit,
    val onStartEdit: (StopListEntity) -> Unit,
    val onSaveEdit: (String, Boolean, Boolean) -> Unit,
    val onCancelEdit: () -> Unit,
    val onClearEditError: () -> Unit,
    val onDelete: (StopListEntity) -> Unit,
)

/**
 * Renders the Stop List: the current keywords, an add form, and an edit dialog.
 *
 * Each row shows the keyword and a summary of its options, and opens the edit dialog when tapped.
 * The add form's text and checkboxes are held here (saveable, so they survive rotation and process
 * death); whether a save succeeded is decided by the ViewModel and reported through [addState].
 * After a successful add the form resets to its defaults: empty text, both options unticked.
 *
 * @param keywords The current stop-list entries.
 * @param addState Outcome of the add form.
 * @param editState The open edit dialog's state, or `null` when no entry is being edited.
 * @param actions Callbacks for every user action.
 */
@Composable
fun StopListTab(
    keywords: List<StopListEntity>,
    addState: StopListAddState,
    editState: StopListEditState?,
    actions: StopListActions,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    // Initialised to the current count so returning to this tab never triggers a spurious reset.
    var handledAdds by rememberSaveable { mutableIntStateOf(addState.completedAdds) }

    LaunchedEffect(addState.completedAdds) {
        if (addState.completedAdds != handledAdds) {
            input = ""
            caseSensitive = false
            wholeWord = false
            handledAdds = addState.completedAdds
        }
    }

    SectionTitle(stringResource(R.string.settings_stop_list_title))
    if (keywords.isEmpty()) {
        Text(
            stringResource(R.string.settings_stop_list_empty),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            stringResource(R.string.stop_list_tap_to_edit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }

    keywords.forEach { entry ->
        StopListRow(
            entry = entry,
            onEdit = { actions.onStartEdit(entry) },
            onDelete = { actions.onDelete(entry) },
        )
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { newValue ->
            input = newValue
            if (addState.error != null) actions.onClearAddError()
        },
        label = { Text(stringResource(R.string.settings_stop_list_hint)) },
        singleLine = true,
        isError = addState.error != null,
        supportingText = addState.error?.let { error -> { Text(stringResource(errorMessage(error))) } },
        modifier = Modifier.fillMaxWidth(),
    )
    StopListOptions(
        wholeWord = wholeWord,
        onWholeWordChange = { checked ->
            wholeWord = checked
            if (addState.error != null) actions.onClearAddError()
        },
        caseSensitive = caseSensitive,
        onCaseSensitiveChange = { checked ->
            caseSensitive = checked
            if (addState.error != null) actions.onClearAddError()
        },
    )
    Button(
        onClick = { actions.onAdd(input, caseSensitive, wholeWord) },
        enabled = input.isNotBlank() && !addState.isSaving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.common_add))
    }

    editState?.let { state ->
        EditStopListDialog(
            state = state,
            onSave = actions.onSaveEdit,
            onCancel = actions.onCancelEdit,
            onClearError = actions.onClearEditError,
        )
    }
}

/**
 * One stop-list entry: the keyword, a one-line summary of its options, and a Delete button.
 *
 * The whole row except the Delete button is the tap target for editing.
 *
 * @param entry The entry to show.
 * @param onEdit Invoked when the row is tapped.
 * @param onDelete Invoked when Delete is tapped.
 */
@Composable
private fun StopListRow(entry: StopListEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val editLabel = stringResource(R.string.stop_list_edit_title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .clickable(onClickLabel = editLabel, onClick = onEdit)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.keyword, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = optionsSummary(entry.wholeWord, entry.caseSensitive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.common_delete))
        }
    }
}

/**
 * The two option checkboxes, shared by the add form and the edit dialog.
 *
 * @param wholeWord Current "whole word only" value.
 * @param onWholeWordChange Invoked with the new "whole word only" value.
 * @param caseSensitive Current "case sensitive" value.
 * @param onCaseSensitiveChange Invoked with the new "case sensitive" value.
 * @param enabled Whether the checkboxes accept input.
 */
@Composable
private fun StopListOptions(
    wholeWord: Boolean,
    onWholeWordChange: (Boolean) -> Unit,
    caseSensitive: Boolean,
    onCaseSensitiveChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    LabeledCheckbox(
        label = stringResource(R.string.stop_list_whole_word),
        checked = wholeWord,
        onCheckedChange = onWholeWordChange,
        enabled = enabled,
    )
    LabeledCheckbox(
        label = stringResource(R.string.stop_list_case_sensitive),
        checked = caseSensitive,
        onCheckedChange = onCaseSensitiveChange,
        enabled = enabled,
    )
}

/**
 * A checkbox whose whole row, label included, is one toggle target.
 *
 * Making the row toggleable (rather than the small box alone) gives a full-size touch target and
 * lets TalkBack announce the label and state as a single checkbox. The label wraps rather than
 * truncating, since the Spanish labels are noticeably longer.
 *
 * @param label The text shown beside the box.
 * @param checked Whether the box is ticked.
 * @param onCheckedChange Invoked with the new value when the row is tapped.
 * @param enabled Whether the row accepts input.
 */
@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onCheckedChange is null so the row, not the box, owns the click and the semantics.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Dialog for editing an existing stop-list entry's text and options.
 *
 * The fields are keyed on the entry's id, so they survive rotation and reset if a different entry
 * is opened. The dialog does not close itself on Save: it closes when the ViewModel clears
 * [StopListEditState] after a successful write, and otherwise stays open showing the error.
 *
 * @param state The edit state, including the stored entry and any error.
 * @param onSave Invoked with (keyword, caseSensitive, wholeWord).
 * @param onCancel Invoked on Cancel, back, or an outside tap.
 * @param onClearError Invoked when a field changes while an error is showing.
 */
@Composable
private fun EditStopListDialog(
    state: StopListEditState,
    onSave: (String, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit,
) {
    val target = state.target
    var text by rememberSaveable(target.id) { mutableStateOf(target.keyword) }
    var caseSensitive by rememberSaveable(target.id) { mutableStateOf(target.caseSensitive) }
    var wholeWord by rememberSaveable(target.id) { mutableStateOf(target.wholeWord) }

    val trimmed = text.trim()
    val hasChanges = (trimmed != target.keyword) ||
        (caseSensitive != target.caseSensitive) ||
        (wholeWord != target.wholeWord)
    val canSave = trimmed.isNotEmpty() && hasChanges && !state.isSaving

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.stop_list_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { newValue ->
                        text = newValue
                        if (state.error != null) onClearError()
                    },
                    label = { Text(stringResource(R.string.settings_stop_list_hint)) },
                    singleLine = true,
                    isError = state.error != null,
                    supportingText = state.error?.let { error -> { Text(stringResource(errorMessage(error))) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                StopListOptions(
                    wholeWord = wholeWord,
                    onWholeWordChange = { checked ->
                        wholeWord = checked
                        if (state.error != null) onClearError()
                    },
                    caseSensitive = caseSensitive,
                    onCaseSensitiveChange = { checked ->
                        caseSensitive = checked
                        if (state.error != null) onClearError()
                    },
                    enabled = !state.isSaving,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text, caseSensitive, wholeWord) }, enabled = canSave) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Builds the one-line options summary shown under a keyword, e.g. "Whole word · Ignores case".
 *
 * @param wholeWord Whether the entry matches whole words only.
 * @param caseSensitive Whether the entry is case sensitive.
 * @return The localized summary.
 */
@Composable
private fun optionsSummary(wholeWord: Boolean, caseSensitive: Boolean): String {
    val scope = stringResource(
        if (wholeWord) R.string.stop_list_summary_whole_word else R.string.stop_list_summary_substring,
    )
    val case = stringResource(
        if (caseSensitive) R.string.stop_list_summary_case_sensitive else R.string.stop_list_summary_ignores_case,
    )
    return "$scope · $case"
}

/**
 * Resolves the message shown for a rejected save.
 *
 * @param error Why the save was rejected.
 * @return String resource ID of the message.
 */
@StringRes
private fun errorMessage(error: StopListError): Int = when (error) {
    StopListError.Duplicate -> R.string.stop_list_error_duplicate
    StopListError.SaveFailed -> R.string.stop_list_error_save_failed
}

/** Minimum height of a tappable row, per Material accessibility guidance. */
private val MIN_TOUCH_TARGET = 48.dp

/** Preview of the Stop List with a mix of option combinations and a duplicate error showing. */
@Preview(showBackground = true, heightDp = 640)
@Composable
private fun StopListTabPreview() {
    SmsFilterTheme {
        Column(Modifier.padding(16.dp)) {
            StopListTab(
                keywords = listOf(
                    StopListEntity(id = 1, keyword = "Avis", wholeWord = true),
                    StopListEntity(id = 2, keyword = "avis"),
                    StopListEntity(id = 3, keyword = "PG&E", caseSensitive = true, wholeWord = true),
                ),
                addState = StopListAddState(error = StopListError.Duplicate),
                editState = null,
                actions = StopListActions(
                    onAdd = { _, _, _ -> },
                    onClearAddError = {},
                    onStartEdit = {},
                    onSaveEdit = { _, _, _ -> },
                    onCancelEdit = {},
                    onClearEditError = {},
                    onDelete = {},
                ),
            )
        }
    }
}
