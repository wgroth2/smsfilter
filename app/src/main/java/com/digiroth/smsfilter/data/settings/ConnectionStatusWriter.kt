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

package com.digiroth.smsfilter.data.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the observed health of the HubSpot connection.
 *
 * Exists so the HubSpot repository depends on an abstraction rather than on [SettingsDataStore],
 * which needs an Android `Context` and therefore cannot be constructed in a JVM unit test. Both the
 * background SMS pipeline and the Settings screen's connection tests write through this, which is
 * what keeps the Connection Health Summary accurate without re-running a live check on every
 * recomposition.
 */
fun interface ConnectionStatusWriter {

    /**
     * Persists the HubSpot connection status.
     *
     * @param status The status observed by the most recent call.
     */
    suspend fun setHubSpotStatus(status: ConnectionStatus)
}

/** The production [ConnectionStatusWriter], writing to the preferences store. */
@Singleton
class DataStoreConnectionStatusWriter @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ConnectionStatusWriter {

    override suspend fun setHubSpotStatus(status: ConnectionStatus) {
        settingsDataStore.setHubSpotStatus(status)
    }
}
