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

package com.digiroth.smsfilter.domain.hubspot

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ConnectHubSpotUseCase]'s interaction with encrypted storage.
 *
 * These exist because the JVM cannot run them. [SecureTokenStore] wraps
 * `EncryptedSharedPreferences`, which needs the Android Keystore; under a plain unit test every
 * read and write degrades to a no-op, so an assertion that a rejected token was cleared would pass
 * whether or not the production code cleared it. That guarantee is the reason the use case exists
 * as a single shared implementation, so it is worth a device to verify.
 *
 * `ConnectHubSpotUseCaseTest` covers the classification and status transitions on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class ConnectHubSpotUseCaseInstrumentedTest {

    private lateinit var secureTokenStore: SecureTokenStore
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var hubSpotRepository: FakeHubSpotRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        secureTokenStore = SecureTokenStore(context)
        // Points at the same file the app's own Hilt-provided DataStore uses (see
        // com.digiroth.smsfilter.di.DataStoreModule), so this exercises the real, shared store
        // rather than an isolated one — matching this instrumented test's existing intent of
        // verifying behavior against the app's actual on-device storage.
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(SettingsDataStore.STORE_NAME) },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        hubSpotRepository = FakeHubSpotRepository()
        secureTokenStore.clearAccessToken()
    }

    @After
    fun tearDown() {
        // These run against the app's own encrypted store on the device; leave nothing behind.
        secureTokenStore.clearAccessToken()
    }

    /**
     * Builds a use case over the real encrypted store and the fake repository.
     *
     * @return The use case under test.
     */
    private fun createUseCase(): ConnectHubSpotUseCase = ConnectHubSpotUseCase(
        secureTokenStore = secureTokenStore,
        hubSpotRepository = hubSpotRepository,
        settingsDataStore = settingsDataStore,
    )

    /**
     * Positive control: a verified token really is persisted.
     *
     * Preconditions: HubSpot answers the verification call.
     * Expected: the trimmed token is readable from encrypted storage afterwards.
     *
     * Without this, the clear-on-failure test below would pass trivially on a device where writing
     * never worked at all.
     */
    @Test
    fun acceptedTokenIsPersisted() = runBlocking {
        hubSpotRepository.outcome = ContactLookupOutcome.NotFound

        val result = createUseCase().invoke("  pat-na1-good-token  ")

        assertEquals(ConnectHubSpotResult.Success, result)
        assertNotNull(secureTokenStore.getAccessToken())
        assertEquals("pat-na1-good-token", secureTokenStore.getAccessToken())
    }

    /**
     * A rejected token must not remain the app's stored credential.
     *
     * Preconditions: a token is written, then HubSpot rejects it as unauthorized.
     * Expected: encrypted storage holds no token afterwards.
     *
     * The write has to happen before the check — the repository reads the token from storage and
     * accepts no parameter — which is exactly what makes the failure path load-bearing. A mistyped
     * token that survived here would silently become the credential every later lookup used.
     */
    @Test
    fun rejectedTokenIsClearedFromEncryptedStorage() = runBlocking {
        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_UNAUTHORIZED)

        val result = createUseCase().invoke("pat-na1-bad-token")

        assertTrue(result is ConnectHubSpotResult.Failure)
        assertNull(secureTokenStore.getAccessToken())
    }

    /**
     * A rejected token must not leave a stale token-present signal behind.
     *
     * Preconditions: a previously accepted token, then a failed connect attempt.
     * Expected: [SecureTokenStore.hasToken] reports absent. The Status dashboard derives its
     * HubSpot indicator from this flow, so a stale `true` would show "Connected" for a credential
     * that is gone.
     */
    @Test
    fun rejectedTokenUpdatesTheObservableTokenSignal() = runBlocking {
        hubSpotRepository.outcome = ContactLookupOutcome.NotFound
        createUseCase().invoke("pat-na1-good-token")
        assertTrue(secureTokenStore.hasToken.value)

        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_UNAUTHORIZED)
        createUseCase().invoke("pat-na1-bad-token")

        assertNull(secureTokenStore.getAccessToken())
        assertTrue(!secureTokenStore.hasToken.value)
    }

    /** A [HubSpotRepository] returning a caller-supplied outcome. */
    private class FakeHubSpotRepository : HubSpotRepository {
        /** The outcome [testConnection] returns. */
        var outcome: ContactLookupOutcome = ContactLookupOutcome.NotFound

        override suspend fun isKnownContact(
            e164Value: String?,
            rawDigits: String,
        ): ContactLookupOutcome = ContactLookupOutcome.NotFound

        override suspend fun testConnection(): ContactLookupOutcome = outcome
    }
}
