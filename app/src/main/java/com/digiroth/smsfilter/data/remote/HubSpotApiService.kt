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

package com.digiroth.smsfilter.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit description of the HubSpot endpoints this app uses.
 *
 * Every method returns `Response<T>` rather than the body directly. That is deliberate: the
 * repository has to branch on the HTTP status code — 401 means the token was revoked, 429 and 5xx
 * are retryable, 400 is not — and an unwrapped return type would surface all of those identically
 * as a thrown `HttpException`.
 */
interface HubSpotApiService {

    /**
     * Searches contacts.
     *
     * @param request The search criteria.
     * @return The raw HTTP response wrapping any matches.
     */
    @POST("crm/v3/objects/contacts/search")
    suspend fun searchContacts(@Body request: HubSpotSearchRequest): Response<HubSpotSearchResponse>

    /**
     * Lightweight call used to validate a token and network path.
     *
     * @param limit Result cap; one keeps the call as cheap as possible.
     * @return The raw HTTP response.
     */
    @GET("crm/v3/objects/contacts")
    suspend fun listContacts(@Query("limit") limit: Int = 1): Response<HubSpotSearchResponse>

    /**
     * Fetches account details, used to display the portal id in Settings.
     *
     * @return The raw HTTP response; may fail with 403 if the token lacks the scope, which is not
     *   treated as a connection problem.
     */
    @GET("account-info/v3/details")
    suspend fun accountInfo(): Response<HubSpotAccountInfo>

    companion object {
        /** HubSpot API root. */
        const val BASE_URL: String = "https://api.hubapi.com/"
    }
}
