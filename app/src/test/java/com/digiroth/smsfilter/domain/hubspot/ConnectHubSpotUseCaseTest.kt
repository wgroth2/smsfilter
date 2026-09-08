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

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [ConnectHubSpotUseCase].
 *
 * Covers the failure classification and the persisted [ConnectionStatus] transitions, which are the
 * parts of the connect sequence two screens must agree on.
 *
 * **Not covered here, deliberately:** that a rejected token is cleared from storage.
 * [SecureTokenStore] wraps `EncryptedSharedPreferences`, which needs the Android Keystore and
 * therefore cannot open under a plain JVM test — every read and write degrades to a no-op, so a
 * "token was cleared" assertion would pass whether or not the production code cleared it. That
 * guarantee is only observable in an instrumented test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectHubSpotUseCaseTest {

    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secureTokenStore: SecureTokenStore
    private lateinit var hubSpotRepository: FakeHubSpotRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("connect_hubspot_test").toFile()
        context = TestContext(tempDir)
        settingsDataStore = SettingsDataStore(context)
        secureTokenStore = SecureTokenStore(context)
        hubSpotRepository = FakeHubSpotRepository()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Builds a use case wired to the fakes created in [setUp].
     *
     * @return The use case under test.
     */
    private fun createUseCase(): ConnectHubSpotUseCase = ConnectHubSpotUseCase(
        secureTokenStore = secureTokenStore,
        hubSpotRepository = hubSpotRepository,
        settingsDataStore = settingsDataStore,
    )

    /**
     * Tests that a reachable HubSpot marks the connection as CONNECTED.
     *
     * Preconditions: the repository answers [ContactLookupOutcome.NotFound], which is a successful
     * call that simply matched nobody.
     * Expected: [ConnectHubSpotResult.Success], and a persisted status of
     * [ConnectionStatus.CONNECTED].
     */
    @Test
    fun `successful test connection records connected status`() = runTest {
        hubSpotRepository.outcome = ContactLookupOutcome.NotFound

        val result = createUseCase().invoke("  pat-na1-token  ")

        assertEquals(ConnectHubSpotResult.Success, result)
        assertEquals(ConnectionStatus.CONNECTED, settingsDataStore.hubSpotStatus.first())
    }

    /**
     * Tests that a rejected token resets the status rather than leaving a stale auth error.
     *
     * Preconditions: the repository fails with [HubSpotRepositoryImpl.REASON_UNAUTHORIZED].
     * Expected: [HubSpotConnectError.INVALID_TOKEN], and a persisted status of
     * [ConnectionStatus.SETUP_INCOMPLETE] — not [ConnectionStatus.AUTH_ERROR], which would describe
     * a token that is no longer stored.
     */
    @Test
    fun `unauthorized failure resets status to setup incomplete`() = runTest {
        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_UNAUTHORIZED)

        val result = createUseCase().invoke("bad-token")

        assertEquals(ConnectHubSpotResult.Failure(HubSpotConnectError.INVALID_TOKEN), result)
        assertEquals(ConnectionStatus.SETUP_INCOMPLETE, settingsDataStore.hubSpotStatus.first())
    }

    /**
     * Tests that the token is trimmed before it reaches storage.
     *
     * Preconditions: a token pasted with surrounding whitespace.
     * Expected: the call still succeeds; trimming must not turn a valid paste into a rejection.
     */
    @Test
    fun `surrounding whitespace does not fail the connect`() = runTest {
        hubSpotRepository.outcome = ContactLookupOutcome.Found

        val result = createUseCase().invoke("\n  pat-na1-token \t")

        assertTrue(result is ConnectHubSpotResult.Success)
    }

    /**
     * Tests that a 403 is reported as a missing scope rather than a bad token.
     *
     * Preconditions: the repository fails with a reason containing `403`.
     * Expected: [HubSpotConnectError.MISSING_SCOPE], so the UI can name the scope to add rather
     * than telling the user to re-enter a token that is actually correct.
     */
    @Test
    fun `forbidden failure classifies as missing scope`() = runTest {
        hubSpotRepository.outcome = ContactLookupOutcome.Failed("http_403")

        val result = createUseCase().invoke("scopeless-token")

        assertEquals(ConnectHubSpotResult.Failure(HubSpotConnectError.MISSING_SCOPE), result)
    }

    /**
     * Tests that an unrecognized failure reason degrades to a network error.
     *
     * Preconditions: the repository fails with a reason matching no known marker.
     * Expected: [HubSpotConnectError.NETWORK], the actionable default — retry.
     */
    @Test
    fun `unrecognized failure classifies as network`() = runTest {
        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_RETRIES_EXHAUSTED)

        val result = createUseCase().invoke("token")

        assertEquals(ConnectHubSpotResult.Failure(HubSpotConnectError.NETWORK), result)
    }

    /**
     * Tests that a missing token classifies as an invalid token.
     *
     * Preconditions: the repository reports [HubSpotRepositoryImpl.REASON_NO_TOKEN], which here can
     * only mean the encrypted store refused the write that just happened.
     * Expected: [HubSpotConnectError.INVALID_TOKEN] — the user's remedy is the same either way.
     */
    @Test
    fun `missing token classifies as invalid token`() = runTest {
        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_NO_TOKEN)

        val result = createUseCase().invoke("token")

        assertEquals(ConnectHubSpotResult.Failure(HubSpotConnectError.INVALID_TOKEN), result)
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

    /** Minimal [Context] giving DataStore a writable directory on the JVM. */
    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.digiroth.smsfilter"
        override fun getFilesDir(): File = baseDir
        override fun getDataDir(): File = baseDir
        override fun getContentResolver(): ContentResolver? = null
    }
}
