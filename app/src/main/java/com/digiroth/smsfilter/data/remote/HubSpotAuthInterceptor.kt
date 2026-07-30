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

package com.digiroth.smsfilter.data.remote

import com.digiroth.smsfilter.data.security.AccessTokenProvider
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the HubSpot Private App token to every outgoing request.
 *
 * Private App tokens do not expire, so there is deliberately no refresh logic here — a 401 means
 * the token was revoked or its scope removed, which only the user can fix by pasting a new one.
 *
 * The token is read per request rather than captured once, so connecting or disconnecting HubSpot
 * takes effect immediately without rebuilding the OkHttp client.
 */
@Singleton
class HubSpotAuthInterceptor @Inject constructor(
    private val accessTokenProvider: AccessTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = accessTokenProvider.accessToken()
        val request = if (token.isNullOrBlank()) {
            // Proceed unauthenticated and let HubSpot answer 401; the repository already refuses to
            // call at all when no token is stored, so this is a belt-and-braces path.
            chain.request()
        } else {
            chain.request().newBuilder()
                .addHeader(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")
                .build()
        }
        return chain.proceed(request)
    }

    companion object {
        /** Standard HTTP authorization header name. */
        const val HEADER_AUTHORIZATION: String = "Authorization"

        /** Scheme prefix HubSpot expects before the token. */
        const val BEARER_PREFIX: String = "Bearer "
    }
}
