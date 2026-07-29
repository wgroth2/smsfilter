/*
 * Copyright (c) 2025 Bill Roth <bill.roth@gmail.com>
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

package com.digiroth.smsfilter.data.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies a one-shot read of the settings the SMS pipeline needs.
 *
 * Exists so the pipeline depends on an abstraction rather than on [SettingsDataStore], which
 * requires an Android `Context` and therefore cannot be constructed in a JVM unit test. Without
 * this seam the pipeline would be untestable even though it contains no `android.*` code itself.
 */
fun interface SettingsSnapshotProvider {

    /**
     * @return An immutable snapshot of the current settings.
     */
    suspend fun snapshot(): SettingsSnapshot
}

/**
 * The production [SettingsSnapshotProvider], delegating to the DataStore-backed settings store.
 *
 * A thin adapter rather than an interface added to [SettingsDataStore] directly, so that class
 * stays exactly as generated in the storage phase.
 */
@Singleton
class DataStoreSettingsSnapshotProvider @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : SettingsSnapshotProvider {

    override suspend fun snapshot(): SettingsSnapshot = settingsDataStore.snapshot()
}
