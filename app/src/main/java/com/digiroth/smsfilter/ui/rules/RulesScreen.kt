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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digiroth.smsfilter.R

/**
 * The two rule editors this screen hosts.
 *
 * @property labelRes Tab label.
 */
private enum class RulesTab(@param:StringRes val labelRes: Int) {
    /** Keywords that exempt a message from opt-out detection entirely. */
    STOP_LIST(R.string.settings_stop_list_title),

    /** Patterns that identify an opt-out request, and the reply each one sends. */
    PATTERNS(R.string.settings_patterns_title),
}

/**
 * The stop list and opt-out patterns, grouped as one peer destination.
 *
 * For official Android documentation on architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Both editors live behind one destination because they are two halves of the same decision — what
 * counts as an opt-out, and what is exempt from being treated as one. Grouping them is also what
 * keeps the bottom bar at three peers rather than five.
 *
 * Each tab takes its own ViewModel, scoped to this destination's `NavBackStackEntry`, so neither
 * holds the other's database flow open.
 *
 * @param stopListViewModel State holder for the stop list tab.
 * @param patternsViewModel State holder for the opt-out patterns tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    stopListViewModel: StopListViewModel = hiltViewModel(),
    patternsViewModel: OptOutPatternsViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableStateOf(RulesTab.STOP_LIST) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable { mutableStateOf(false) }

    val keywords by stopListViewModel.keywords.collectAsStateWithLifecycle()
    val patterns by patternsViewModel.patterns.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                actions = {
                    // Reset only applies to patterns; offering it on the stop list would imply a
                    // set of default keywords, and there is none.
                    if (selectedTab == RulesTab.PATTERNS) {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.rules_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rules_reset_defaults)) },
                                onClick = {
                                    showOverflow = false
                                    showResetConfirmation = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                RulesTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (selectedTab) {
                    RulesTab.STOP_LIST -> StopListTab(
                        keywords = keywords,
                        onAdd = stopListViewModel::add,
                        onDelete = stopListViewModel::delete,
                    )

                    RulesTab.PATTERNS -> OptOutPatternsTab(
                        patterns = patterns,
                        onAdd = patternsViewModel::add,
                        onUpdate = patternsViewModel::update,
                        onDelete = patternsViewModel::delete,
                    )
                }
            }
        }
    }

    if (showResetConfirmation) {
        ResetPatternsDialog(
            onConfirm = {
                patternsViewModel.resetToDefaults()
                showResetConfirmation = false
            },
            onDismiss = { showResetConfirmation = false },
        )
    }
}

/**
 * Confirms the destructive reset of every opt-out pattern.
 *
 * Reset discards user-authored patterns as well as the seeded defaults and cannot be undone, so it
 * is never performed straight from the menu item.
 *
 * @param onConfirm Invoked when the user accepts the reset.
 * @param onDismiss Invoked on cancel, back, or an outside tap.
 */
@Composable
private fun ResetPatternsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_reset_defaults)) },
        text = { Text(stringResource(R.string.rules_reset_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.rules_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
