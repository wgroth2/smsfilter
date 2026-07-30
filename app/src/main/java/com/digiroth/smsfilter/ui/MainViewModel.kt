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

package com.digiroth.smsfilter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where the app should open on launch. */
enum class StartDestination {
    /** First run: show the setup wizard. */
    ONBOARDING,

    /** Setup already complete: show the main UI. */
    MAIN,
}

/**
 * Decides the app's start destination.
 *
 * `firstRunComplete` is a `Flow`, so its first value arrives asynchronously. [startDestination]
 * therefore begins as `null`, meaning "not yet known", and the UI shows a neutral loading state until
 * it resolves. Defaulting to onboarding instead would make returning users see the wizard flash on
 * every cold start.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsDataStore: SettingsDataStore,
) : ViewModel() {

    /** The destination to open, or `null` while still being read. */
    val startDestination: StateFlow<StartDestination?> = settingsDataStore.firstRunComplete
        .map { complete -> if (complete) StartDestination.MAIN else StartDestination.ONBOARDING }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    private companion object {
        /** Keeps the upstream flow alive briefly across configuration changes. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
