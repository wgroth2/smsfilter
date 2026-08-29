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
 * Verifies HubSpot contact search behavior, E.164 and raw digit fallback queries,
 * authentication headers, error classification (e.g. 401 Unauthorized), retry policies,
 * and connection health testing.
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

    /**
     * Tests that finding a matching contact in HubSpot returns Found.
     *
     * Preconditions: MockWebServer enqueues a matching contact response.
     * Expected: [HubSpotRepository.isKnownContact] returns [ContactLookupOutcome.Found].
     */
    @Test
    fun `contact match returns Found`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(1, server.requestCount)
    }

    /**
     * Tests that receiving empty results on both E.164 and raw digit queries returns NotFound.
     *
     * Preconditions: MockWebServer enqueues two empty responses.
     * Expected: [HubSpotRepository.isKnownContact] returns [ContactLookupOutcome.NotFound] after trying both search terms.
     */
    @Test
    fun `no match on either search returns NotFound`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.NotFound, outcome)
        assertEquals("both search terms must be tried", 2, server.requestCount)
    }

    /**
     * Tests that a raw digit fallback query is performed only when the primary E.164 search returns empty.
     *
     * Preconditions: First query returns empty body, second query returns match body.
     * Expected: Outcome is Found, 2 requests made (first with E.164, second with raw digits).
     */
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

    /**
     * Tests that when the initial E.164 search succeeds, no second search is performed.
     *
     * Preconditions: First query returns match body.
     * Expected: Exactly 1 request made.
     */
    @Test
    fun `no second search when the first already matched`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("a match must not trigger the fallback", 1, server.requestCount)
    }

    /**
     * Tests that when E.164 and raw digits are identical, only one query is dispatched.
     *
     * Preconditions: e164Value == rawDigits.
     * Expected: Outcome is NotFound and request count is 1.
     */
    @Test
    fun `identical e164 and raw digits are searched only once`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.isKnownContact(RAW_DIGITS, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.NotFound, outcome)
        assertEquals("duplicate terms must be de-duplicated", 1, server.requestCount)
    }

    /**
     * Tests that when E.164 is null, only the raw digits are searched.
     *
     * Preconditions: e164Value is null.
     * Expected: Exactly 1 query dispatched using raw digits, returning Found.
     */
    @Test
    fun `null e164 searches the raw digits only`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(null, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains(RAW_DIGITS))
    }

    /**
     * Tests that queries target HubSpot's searchable calculated phone property with CONTAINS_TOKEN operator.
     *
     * Preconditions: Standard search request.
     * Expected: Request payload filters on hs_searchable_calculated_phone_number using CONTAINS_TOKEN.
     */
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

    /**
     * Tests that outgoing API requests include the Authorization Bearer header.
     *
     * Preconditions: Access token configured in token provider.
     * Expected: HTTP request header "Authorization" equals "Bearer <token>".
     */
    @Test
    fun `every request carries the bearer token`() = runTest {
        server.enqueue(jsonResponse(MATCH_BODY))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("Bearer $TOKEN", server.takeRequest().getHeader("Authorization"))
    }

    /**
     * Tests that a 401 Unauthorized response records AUTH_ERROR and returns Failed.
     *
     * Preconditions: MockWebServer returns HTTP 401.
     * Expected: Outcome is [ContactLookupOutcome.Failed] with UNAUTHORIZED reason, and ConnectionStatus.AUTH_ERROR is recorded.
     */
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

    /**
     * Tests that receiving a 401 Unauthorized response does not delete or clear the stored token.
     *
     * Preconditions: 401 Unauthorized response.
     * Expected: Stored token remains unchanged in the provider.
     */
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

    /**
     * Tests that a 401 Unauthorized response is not retried.
     *
     * Preconditions: 401 Unauthorized response.
     * Expected: Only 1 request attempt is made.
     */
    @Test
    fun `unauthorized is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals("a 401 can never succeed on retry", 1, server.requestCount)
    }

    /**
     * Tests that a missing (null) access token immediately returns Failed without making any network request.
     *
     * Preconditions: Access token is null.
     * Expected: Outcome is Failed with REASON_NO_TOKEN and request count is 0.
     */
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

    /**
     * Tests that a whitespace-only token is treated as missing and bypasses network calls.
     *
     * Preconditions: Access token is "   ".
     * Expected: Outcome is Failed and request count is 0.
     */
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

    /**
     * Tests that rate limits (HTTP 429) are retried up to MAX_ATTEMPTS before returning Failed.
     *
     * Preconditions: Server responds with HTTP 429 for all attempts.
     * Expected: [HubSpotRepositoryImpl.MAX_ATTEMPTS] requests made, outcome is Failed with REASON_RETRIES_EXHAUSTED.
     */
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

    /**
     * Tests that server errors (HTTP 500) are retried up to MAX_ATTEMPTS before returning Failed.
     *
     * Preconditions: Server responds with HTTP 500 for all attempts.
     * Expected: Exactly MAX_ATTEMPTS requests made and outcome is Failed.
     */
    @Test
    fun `server error is retried up to the attempt cap then fails`() = runTest {
        repeat(HubSpotRepositoryImpl.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setResponseCode(500))
        }

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals(HubSpotRepositoryImpl.MAX_ATTEMPTS, server.requestCount)
    }

    /**
     * Tests that a transient error followed by a successful 200 response returns Found.
     *
     * Preconditions: First attempt returns 500, second attempt returns match body.
     * Expected: Outcome is Found with 2 total request attempts.
     */
    @Test
    fun `a retry that succeeds returns the real result`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(jsonResponse(MATCH_BODY))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertEquals(ContactLookupOutcome.Found, outcome)
        assertEquals(2, server.requestCount)
    }

    /**
     * Tests that client error 400 Bad Request is not retried.
     *
     * Preconditions: Server responds with HTTP 400.
     * Expected: Outcome is Failed and only 1 request attempt is made.
     */
    @Test
    fun `bad request is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val outcome = repository.isKnownContact(E164, RAW_DIGITS)

        assertTrue(outcome is ContactLookupOutcome.Failed)
        assertEquals("a 400 is a client bug and cannot succeed on retry", 1, server.requestCount)
    }

    /**
     * Tests that an exhaustion of retries on 503 Service Unavailable returns Failed, not NotFound.
     *
     * Preconditions: Server returns 503 for all attempts.
     * Expected: Outcome is not [ContactLookupOutcome.NotFound] so that service outages are not treated as unknown senders.
     */
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

    /**
     * Tests that testConnection succeeds on HTTP 200 and persists the CONNECTED status.
     *
     * Preconditions: Server returns HTTP 200 empty response.
     * Expected: Outcome is not Failed and ConnectionStatus.CONNECTED is recorded.
     */
    @Test
    fun `connection test succeeds on 200 and records CONNECTED`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        val outcome = repository.testConnection()

        assertTrue("both Found and NotFound mean healthy", outcome !is ContactLookupOutcome.Failed)
        assertTrue(statusWriter.written.contains(ConnectionStatus.CONNECTED))
    }

    /**
     * Tests that testConnection queries the contacts endpoint with limit=1.
     *
     * Preconditions: Executing testConnection against MockWebServer.
     * Expected: HTTP GET request to "/crm/v3/objects/contacts" with "limit=1".
     */
    @Test
    fun `connection test hits the lightweight contacts endpoint`() = runTest {
        server.enqueue(jsonResponse(EMPTY_BODY))

        repository.testConnection()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.path?.contains("crm/v3/objects/contacts") == true)
        assertTrue("must request only one record", request.path?.contains("limit=1") == true)
    }

    /**
     * Tests that testConnection returns Failed on network transport errors.
     *
     * Preconditions: Server is shut down before testConnection is invoked.
     * Expected: Outcome is [ContactLookupOutcome.Failed].
     */
    @Test
    fun `connection test fails on a transport error`() = runTest {
        // Shutting the server down produces a genuine connection failure rather than an HTTP status.
        server.shutdown()

        val outcome = repository.testConnection()

        assertTrue(outcome is ContactLookupOutcome.Failed)
    }

    /**
     * Tests that testConnection without a token makes no network requests and returns Failed.
     *
     * Preconditions: Token is null.
     * Expected: Outcome is [ContactLookupOutcome.Failed] with 0 network requests.
     */
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
