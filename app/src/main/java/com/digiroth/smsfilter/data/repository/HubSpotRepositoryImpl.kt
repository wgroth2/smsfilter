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

import androidx.annotation.VisibleForTesting
import com.digiroth.smsfilter.data.remote.HubSpotApiService
import com.digiroth.smsfilter.data.remote.HubSpotFilter
import com.digiroth.smsfilter.data.remote.HubSpotFilterGroup
import com.digiroth.smsfilter.data.remote.HubSpotSearchRequest
import com.digiroth.smsfilter.data.remote.HubSpotSearchResponse
import com.digiroth.smsfilter.data.security.AccessTokenProvider
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.ConnectionStatusWriter
import com.digiroth.smsfilter.util.AppLogger
import kotlinx.coroutines.delay
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production [HubSpotRepository], backed by the HubSpot CRM v3 search API.
 *
 * Three behaviours here are load-bearing rather than incidental:
 *
 * 1. **Failure is never reported as "not a contact."** Every network error, timeout, rate limit, and
 *    auth rejection returns [ContactLookupOutcome.Failed]. The SMS pipeline treats a failure as
 *    "keep processing this message", which is the safe direction, but it needs the distinction to
 *    surface an outage in the Connection Health Summary rather than silently behaving as though the
 *    CRM were empty.
 * 2. **Two searches, not one.** HubSpot's normalized `hs_searchable_calculated_phone_number` is
 *    searched with the E.164 form first and then with the raw incoming digits. Numbers entered into
 *    HubSpot in local format do not always match an E.164 query, and a missed match here means
 *    auto-replying "stop" to a real customer.
 * 3. **No token means no request.** If nothing is stored, the call short-circuits rather than
 *    issuing an unauthenticated request that HubSpot would reject and that would pollute the
 *    connection status with an auth error the user cannot act on.
 *
 * The `Use HubSpot` toggle is deliberately not consulted here — `SmsProcessingPipeline` gates on it
 * before calling this class, and duplicating the check would put that policy in two places.
 *
 * @property apiService Injected rather than constructed internally so tests can point Retrofit at a
 *   `MockWebServer` URL.
 * @property accessTokenProvider Reads the stored token without requiring an Android `Context`.
 * @property connectionStatusWriter Records observed health for the Settings indicator.
 * @property initialBackoffMillis First retry delay; doubles each attempt. Tests pass zero so the
 *   retry paths cost no wall-clock time.
 */
@Singleton
class HubSpotRepositoryImpl @VisibleForTesting constructor(
    private val apiService: HubSpotApiService,
    private val accessTokenProvider: AccessTokenProvider,
    private val connectionStatusWriter: ConnectionStatusWriter,
    private val initialBackoffMillis: Long,
    private val logger: AppLogger,
) : HubSpotRepository {

    /** Production constructor used by Hilt. */
    @Inject
    constructor(
        apiService: HubSpotApiService,
        accessTokenProvider: AccessTokenProvider,
        connectionStatusWriter: ConnectionStatusWriter,
        logger: AppLogger,
    ) : this(
        apiService = apiService,
        accessTokenProvider = accessTokenProvider,
        connectionStatusWriter = connectionStatusWriter,
        initialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS,
        logger = logger,
    )

    override suspend fun isKnownContact(
        e164Value: String?,
        rawDigits: String,
    ): ContactLookupOutcome {
        if (accessTokenProvider.accessToken().isNullOrBlank()) {
            logger.debug(TAG, "No HubSpot token stored; skipping lookup without a network call")
            return ContactLookupOutcome.Failed(reason = REASON_NO_TOKEN)
        }

        // Query terms in priority order, de-duplicated so an address whose E.164 form equals its
        // raw digits does not cost two identical requests.
        val searchTerms = listOfNotNull(
            e164Value?.takeIf(String::isNotBlank),
            rawDigits.takeIf(String::isNotBlank),
        ).distinct()

        if (searchTerms.isEmpty()) {
            return ContactLookupOutcome.NotFound
        }

        for (term in searchTerms) {
            when (val outcome = searchWithRetry(term)) {
                is ContactLookupOutcome.Found -> return outcome
                // A failure short-circuits: if the first search could not complete, the fallback is
                // very unlikely to fare better and would only double the delay before the SMS
                // pipeline can move on.
                is ContactLookupOutcome.Failed -> return outcome
                is ContactLookupOutcome.NotFound -> Unit
            }
        }

        return ContactLookupOutcome.NotFound
    }

    override suspend fun testConnection(): ContactLookupOutcome {
        if (accessTokenProvider.accessToken().isNullOrBlank()) {
            return ContactLookupOutcome.Failed(reason = REASON_NO_TOKEN)
        }

        return execute(
            describe = "connection test",
        ) { apiService.listContacts(limit = 1) }
    }

    /**
     * Runs one search term through the retry policy.
     *
     * @param term The phone value to search for.
     * @return Whether a contact matched, or a failure.
     */
    private suspend fun searchWithRetry(term: String): ContactLookupOutcome = execute(
        describe = "contact search",
    ) { apiService.searchContacts(buildSearchRequest(term)) }

    /**
     * Executes a HubSpot call with bounded exponential backoff.
     *
     * Retries transport failures, HTTP 429, and 5xx up to [MAX_ATTEMPTS] times. A 401 and any other
     * 4xx are returned immediately: neither can succeed on retry, and retrying a 401 would waste
     * the expedited worker's budget while the user is unaware their token needs replacing.
     *
     * @param describe Short label used in log messages.
     * @param call The HubSpot call to perform.
     * @return The resolved outcome.
     */
    private suspend fun execute(
        describe: String,
        call: suspend () -> Response<HubSpotSearchResponse>,
    ): ContactLookupOutcome {
        var backoff = initialBackoffMillis

        repeat(MAX_ATTEMPTS) { attemptIndex ->
            val attempt = attemptIndex + 1
            val response = runCatching { call() }
                .onFailure { error ->
                    logger.warn(TAG, "$describe attempt $attempt failed in transport", error)
                }
                .getOrNull()

            if (response != null) {
                when {
                    response.isSuccessful -> {
                        connectionStatusWriter.setHubSpotStatus(ConnectionStatus.CONNECTED)
                        val body = response.body()
                        return if (body?.hasMatch == true) {
                            ContactLookupOutcome.Found
                        } else {
                            ContactLookupOutcome.NotFound
                        }
                    }

                    response.code() == HTTP_UNAUTHORIZED -> {
                        // The token was revoked or its scope removed. Record it so the Settings
                        // screen shows "Token invalid", but never delete the stored token — the
                        // specification requires it be retained so the user can see and fix the
                        // problem rather than silently losing their configuration.
                        logger.error(TAG, "$describe rejected with 401; marking token invalid")
                        connectionStatusWriter.setHubSpotStatus(ConnectionStatus.AUTH_ERROR)
                        return ContactLookupOutcome.Failed(reason = REASON_UNAUTHORIZED)
                    }

                    isRetryable(response.code()) -> {
                        logger.warn(TAG, "$describe attempt $attempt got retryable HTTP ${response.code()}")
                    }

                    else -> {
                        logger.error(TAG, "$describe failed with non-retryable HTTP ${response.code()}")
                        connectionStatusWriter.setHubSpotStatus(ConnectionStatus.DISCONNECTED)
                        return ContactLookupOutcome.Failed(reason = "http_${response.code()}")
                    }
                }
            }

            val isLastAttempt = attempt >= MAX_ATTEMPTS
            if (!isLastAttempt) {
                delay(backoff)
                backoff *= BACKOFF_MULTIPLIER
            }
        }

        logger.error(TAG, "$describe exhausted $MAX_ATTEMPTS attempts")
        connectionStatusWriter.setHubSpotStatus(ConnectionStatus.DISCONNECTED)
        return ContactLookupOutcome.Failed(reason = REASON_RETRIES_EXHAUSTED)
    }

    /**
     * Builds the search body for one phone value.
     *
     * Filters on HubSpot's normalized calculated property rather than the raw `phone` or
     * `mobilephone` fields. Those store whatever the user typed — "(650) 555-1234" — so an exact
     * match against an E.164 query misses real contacts. `CONTAINS_TOKEN` against the calculated
     * property tolerates the remaining formatting differences.
     *
     * @param term The phone value to search for.
     * @return The request body.
     */
    private fun buildSearchRequest(term: String): HubSpotSearchRequest = HubSpotSearchRequest(
        filterGroups = listOf(
            HubSpotFilterGroup(
                filters = listOf(
                    HubSpotFilter(
                        propertyName = SEARCHABLE_PHONE_PROPERTY,
                        operator = OPERATOR_CONTAINS_TOKEN,
                        value = term,
                    ),
                ),
            ),
        ),
        // No properties requested: the app only needs to know whether a match exists, and asking
        // for none means HubSpot returns no personal data at all.
        properties = emptyList(),
        limit = SEARCH_RESULT_LIMIT,
    )

    private fun isRetryable(code: Int): Boolean =
        code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR_FLOOR

    companion object {
        private const val TAG = "HubSpotRepository"

        /** HubSpot's normalized, searchable phone property. */
        const val SEARCHABLE_PHONE_PROPERTY: String = "hs_searchable_calculated_phone_number"

        /** Search operator tolerant of residual formatting differences. */
        const val OPERATOR_CONTAINS_TOKEN: String = "CONTAINS_TOKEN"

        /** Total attempts per call, including the first. */
        const val MAX_ATTEMPTS: Int = 3

        /** Production first-retry delay in milliseconds; doubles per attempt. */
        const val DEFAULT_INITIAL_BACKOFF_MILLIS: Long = 500L

        /** Factor applied to the backoff after each failed attempt. */
        const val BACKOFF_MULTIPLIER: Long = 2L

        /** One result is enough to answer "is this a known contact". */
        const val SEARCH_RESULT_LIMIT: Int = 1

        /** Reported when no token is stored. */
        const val REASON_NO_TOKEN: String = "no_token"

        /** Reported when HubSpot rejects the token. */
        const val REASON_UNAUTHORIZED: String = "unauthorized"

        /** Reported when every attempt failed. */
        const val REASON_RETRIES_EXHAUSTED: String = "retries_exhausted"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_FLOOR = 500
    }
}
