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

package com.digiroth.smsfilter.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides the [DataStore] backing [SettingsDataStore].
 *
 * This binding exists as an explicit `@Provides` method, rather than the simpler
 * `Context.preferencesDataStore(...)` property delegate, specifically so its write-actor
 * [CoroutineScope] is a constructor parameter and not hardcoded. The delegate's default scope is
 * `CoroutineScope(Dispatchers.IO + SupervisorJob())` — a real thread pool that a JVM test's
 * `Dispatchers.setMain(testDispatcher)` cannot reach. That mismatch let a `viewModelScope.launch`
 * awaiting a [SettingsDataStore] write resume on `Dispatchers.Main` at a non-deterministic later
 * moment, occasionally *after* a test's `tearDown()` had already called
 * `kotlinx.coroutines.test.resetMain`, crashing with "Dispatchers.Main was accessed... test
 * dispatcher was unset" while a later, unrelated test happened to be running. Tests now build
 * their own [DataStore] on the same dispatcher they pass to `Dispatchers.setMain`, so the write
 * actor lives inside the test's own virtual scheduler and `advanceUntilIdle()` drains it
 * deterministically before teardown.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * Builds the singleton settings preferences [DataStore].
     *
     * @param context Application context supplying the backing file location.
     * @return The process-wide preferences [DataStore].
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(SettingsDataStore.STORE_NAME) },
        )
}
