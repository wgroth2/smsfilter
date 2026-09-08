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

package com.digiroth.smsfilter.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Builds a [DataStore] for [SettingsDataStore] under a JVM unit test.
 *
 * Production code obtains its [DataStore] from
 * `com.digiroth.smsfilter.di.DataStoreModule`, which scopes the store's internal write actor to
 * a real `Dispatchers.IO` thread pool. Handing a test that same real dispatcher would let a
 * `viewModelScope.launch` that awaits a write outlive the test method: the write commits on a
 * real background thread, and its completion resumes the awaiting coroutine on
 * `Dispatchers.Main` at a later, non-deterministic moment — including after `tearDown()` has
 * already called `kotlinx.coroutines.test.resetMain`, which crashes with "Dispatchers.Main was
 * accessed... test dispatcher was unset" while some unrelated test happens to be running.
 *
 * Building the store on the *same* [dispatcher] the test passes to `Dispatchers.setMain` keeps
 * every write inside that test's own virtual scheduler, so
 * `kotlinx.coroutines.test.TestCoroutineScheduler.advanceUntilIdle` drains it deterministically
 * before teardown resets `Dispatchers.Main`.
 *
 * @param directory Writable directory backing the store's file; typically a per-test temp dir
 * that the caller deletes in `tearDown()`.
 * @param dispatcher Dispatcher the store's internal write actor runs on. Pass the same
 * `TestDispatcher` instance the test passed to `Dispatchers.setMain`.
 * @return A [DataStore] suitable for constructing [SettingsDataStore] in a test.
 */
fun createTestSettingsDataStore(
    directory: File,
    dispatcher: CoroutineDispatcher,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    scope = CoroutineScope(SupervisorJob() + dispatcher),
    produceFile = { File(directory, "${SettingsDataStore.STORE_NAME}.preferences_pb") },
)
