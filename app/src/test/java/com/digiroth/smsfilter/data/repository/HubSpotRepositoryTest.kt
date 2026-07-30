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

package com.digiroth.smsfilter.data.repository

import com.digiroth.smsfilter.data.remote.HubSpotApiService
import com.digiroth.smsfilter.data.remote.HubSpotAuthInterceptor
import com.digiroth.smsfilter.data.security.AccessTokenProvider
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.ConnectionStatusWriter
import com.digiroth.smsfilter.util.NoOpLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * JVM unit tests for [HubSpotRepositoryImpl], driven by MockWebServer.
 *
 * Retrofit is built here against `server.url("/")`, which is only possible because the repository
 * takes [HubSpotApiService] as a constructor parameter rather than building its own Retrofit. The
 * backoff is set to zero so the retry tests cost no wall-clock time.
 *
 * The auth interceptor is included in the client under test, so header assertions exercise the real
 * production interceptor rather than a stand-in.
 */
class HubSpotRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: HubSpotRepositoryImpl

    private val tokenProvider = FakeAccessTokenProvider()
    private val statusWriter = FakeConnectionStatusWriter()

    private class FakeAccessTokenProvider(var token: String? = TOKEN) : AccessTokenProvider {
        override fun accessToken(): String? = token
    }

    private class FakeConnectionStatusWriter : ConnectionStatusWriter {
        /** Every status written, in order. */
        val written: MutableList<ConnectionStatus> = mutableListOf()

        override suspend fun setHubSpotStatus(status: ConnectionStatus) {
            written += status
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(HubSpotAuthInterceptor(tokenProvider))
            .build()
        val service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(HubSpotApiService::class.java)

        repository = HubSpotRepositoryImpl(
            apiService = service,
            accessTokenProvider = tokenProvider,
            connectionStatusWriter = statusWriter,
            initialBackoffMillis = 0L,
            logger = NoOpLogger,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------

    @Test
    fun `contact match returns Found`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `no match on either search returns NotFound`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.NotFound, outcome)
        assertEquals("both search terms must be tried", 2, server.requestCount)
    }

    @Test
    fun `raw digit fallback fires only after the e164 search finds nothing`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(2, server.requestCount)

        val firstBody = server.takeRequest().body.readUtf8()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue("first search must use the E.164 value", firstBody.contains(E164))
        assertTrue("fallback search must use the raw digits", secondBody.contains(RAW_DIGITS))
    }

    @Test
    fun `no second search when the first already matched`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("a match must not trigger the fallback", 1, server.requestCount)
    }

    @Test
    fun `identical e164 and raw digits are searched only once`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.isKnownContact(RAW_DIGITS, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.NotFound, outcome)
        assertEquals("duplicate terms must be de-duplicated", 1, server.requestCount)
    }

    @Test
    fun `null e164 searches the raw digits only`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(null, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains(RAW_DIGITS))
    }

    @Test
    fun `searches the normalized calculated phone property`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        repository.isKnownContact(E164, RAW_DIGITS)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            "must filter on hs_searchable_calculated_phone_number, not raw phone fields",
            body.contains(HubSpotRepositoryImpl.SEARCHABLE_PHONE_PROPERTY),
        )
        assertTrue(body.contains(HubSpotRepositoryImpl.OPERATOR_CONTAINS_TOKEN))
    }

    // ---------------------------------------------------------------------
    // Authentication
    // ---------------------------------------------------------------------

    @Test
    fun `every request carries the bearer token`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("Bearer $TOKEN", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `unauthorized writes AUTH_ERROR and returns Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(
            HubSpotRepositoryImpl.REASON_UNAUTHORIZED,
            (outcome as ContactLookupOutcome.Failed).reason,
        )
        assertTrue(
            "the Settings indicator must show a token problem",
            statusWriter.written.contains(ConnectionStatus.AUTH_ERROR),
        )
    }

    @Test
    fun `unauthorized does not clear the stored token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(
            "the token must be retained so the user can see and fix the problem",
            TOKEN,
            tokenProvider.token,
        )
    }

    @Test
    fun `unauthorized is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("a 401 can never succeed on retry", 1, server.requestCount)
    }

    @Test
    fun `missing token returns Failed without any network call`() = runTest {
        tokenProvider.token = null

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(
            HubSpotRepositoryImpl.REASON_NO_TOKEN,
            (outcome as ContactLookupOutcome.Failed).reason,
        )
        assertEquals("no request may be made without a token", 0, server.requestCount)
    }

    @Test
    fun `blank token is treated as missing`() = runTest {
        tokenProvider.token = "   "

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------------
    // Retry policy
    // ---------------------------------------------------------------------

    @Test
    fun `rate limit is retried up to the attempt cap then fails`() = runTest {
        repeat(HubSpotRepositoryImpl.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(429))
        }

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(
            HubSpotRepositoryImpl.REASON_RETRIES_EXHAUSTED,
            (outcome as ContactLookupOutcome.Failed).reason,
        )
        assertEquals(HubSpotRepositoryImpl.MAX_ATTEMPTS, server.requestCount)
    }

    @Test
    fun `server error is retried up to the attempt cap then fails`() = runTest {
        repeat(HubSpotRepositoryImpl.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(HubSpotRepositoryImpl.MAX_ATTEMPTS, server.requestCount)
    }

    @Test
    fun `a retry that succeeds returns the real result`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `bad request is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals("a 400 is a client bug and cannot succeed on retry", 1, server.requestCount)
    }

    @Test
    fun `lookup failure never reports NotFound`() = runTest {
        // The distinction the SMS pipeline depends on: an outage must not look like an empty CRM.
        repeat(HubSpotRepositoryImpl.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(503))
        }

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(
            "a failure reported as NotFound would hide a HubSpot outage",
            outcome !is ContactLookupOutcome.NotFound,
        )
    }

    // ---------------------------------------------------------------------
    // Connection test
    // ---------------------------------------------------------------------

    @Test
    fun `connection test succeeds on 200 and records CONNECTED`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.testConnection()

        assertTrue("both Found and NotFound mean healthy", outcome !is ContactLookupOutcome.Failed)
        assertTrue(statusWriter.written.contains(ConnectionStatus.CONNECTED))
    }

    @Test
    fun `connection test hits the lightweight contacts endpoint`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        repository.testConnection()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path?.contains("crm/v3/objects/contacts") == true)
        assertTrue("must request only one record", request.path?.contains("limit=1") == true)
    }

    @Test
    fun `connection test fails on a transport error`() = runTest {
        // Shutting the server down produces a genuine connection failure rather than an HTTP status.
        server.shutdown()

        val outcome = repository.testConnection()

        assertTrue(outcome is ContactLookupOutcome.Failed)
    }

    @Test
    fun `connection test without a token makes no request`() = runTest {
        tokenProvider.token = null

        val outcome = repository.testConnection()

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(0, server.requestCount)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val TOKEN = "pat-na1-test-token"
        const val E164 = "+16505551234"
        const val RAW_DIGITS = "6505551234"

        const val MATCH_BODY = """{"total":1,"results":[{"id":"501"}]}"""
        const val EMPTY_BODY = """{"total":0,"results":[]}"""
    }
}
