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

package com.digiroth.smsfilter.ui.settings

import com.digiroth.smsfilter.data.settings.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM unit tests for [ConnectionHealthEvaluator]. */
class ConnectionHealthEvaluatorTest {

    private val evaluator = ConnectionHealthEvaluator()

    @Test
    fun `disabled hubspot is off regardless of token or status`() {
        ConnectionStatus.entries.forEach { status ->
            assertEquals(
                "a deliberately disabled integration is never an error",
                HubSpotHealth.OFF,
                evaluator.evaluateHubSpot(isEnabled = false, hasToken = true, lastStatus = status),
            )
        }
    }

    @Test
    fun `enabled without a token is setup incomplete`() {
        assertEquals(
            HubSpotHealth.SETUP_INCOMPLETE,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = false,
                lastStatus = ConnectionStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun `stale auth error with no token is setup incomplete not an error`() {
        // Reachable immediately after a failed Connect & Test: testConnection persists AUTH_ERROR
        // on a 401, and the token is then cleared. Showing red there would blame the user for a
        // credential the app no longer holds.
        assertEquals(
            HubSpotHealth.SETUP_INCOMPLETE,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = false,
                lastStatus = ConnectionStatus.AUTH_ERROR,
            ),
        )
    }

    @Test
    fun `enabled with token and successful check is connected`() {
        assertEquals(
            HubSpotHealth.CONNECTED,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = true,
                lastStatus = ConnectionStatus.CONNECTED,
            ),
        )
    }

    @Test
    fun `enabled with token and unknown status is connected`() {
        // A saved token implies a successful test, because the connect flow only persists on success.
        assertEquals(
            HubSpotHealth.CONNECTED,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = true,
                lastStatus = ConnectionStatus.UNKNOWN,
            ),
        )
    }

    @Test
    fun `enabled with token and auth error is an error`() {
        assertEquals(
            HubSpotHealth.ERROR,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = true,
                lastStatus = ConnectionStatus.AUTH_ERROR,
            ),
        )
    }

    @Test
    fun `enabled with token and disconnected is an error`() {
        assertEquals(
            HubSpotHealth.ERROR,
            evaluator.evaluateHubSpot(
                isEnabled = true,
                hasToken = true,
                lastStatus = ConnectionStatus.DISCONNECTED,
            ),
        )
    }

    @Test
    fun `all four hubspot states are reachable`() {
        val reached = setOf(
            evaluator.evaluateHubSpot(false, true, ConnectionStatus.CONNECTED),
            evaluator.evaluateHubSpot(true, false, ConnectionStatus.UNKNOWN),
            evaluator.evaluateHubSpot(true, true, ConnectionStatus.CONNECTED),
            evaluator.evaluateHubSpot(true, true, ConnectionStatus.AUTH_ERROR),
        )

        assertEquals(HubSpotHealth.entries.toSet(), reached)
    }

    @Test
    fun `google contacts follows the live permission`() {
        assertEquals(
            GoogleContactsHealth.CONNECTED,
            evaluator.evaluateGoogleContacts(hasPermission = true),
        )
        assertEquals(
            GoogleContactsHealth.PERMISSION_REQUIRED,
            evaluator.evaluateGoogleContacts(hasPermission = false),
        )
    }
}
