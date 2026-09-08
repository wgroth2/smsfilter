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

import android.util.Log
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.ConnectionStatus
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/** Why a "Connect & Test" attempt failed, so the UI can explain the specific problem. */
enum class HubSpotConnectError {
    /** The token was rejected as invalid or revoked. */
    INVALID_TOKEN,

    /** The token is valid but lacks the crm.objects.contacts.read scope. */
    MISSING_SCOPE,

    /** HubSpot could not be reached. */
    NETWORK,
}

/** The outcome of attempting to connect a HubSpot Private App token. */
sealed interface ConnectHubSpotResult {

    /** The token was stored and verified; HubSpot is connected. */
    data object Success : ConnectHubSpotResult

    /**
     * The token was rejected or unreachable, and has been cleared again.
     *
     * @property error The specific cause, so the message can be actionable.
     */
    data class Failure(val error: HubSpotConnectError) : ConnectHubSpotResult
}

/**
 * Stores and verifies a HubSpot Private App access token.
 *
 * For official Android documentation on the domain layer and use cases, see:
 * - Domain layer: [https://developer.android.com/topic/architecture/domain-layer](https://developer.android.com/topic/architecture/domain-layer)
 *
 * Extracted from `SettingsViewModel` because two screens now perform this flow: the Settings screen
 * and step 4 of the onboarding wizard. The sequence has one non-obvious ordering constraint —
 * described on [invoke] — and a second, divergent copy of it would be free to lose that constraint
 * silently, leaving a rejected token as the app's stored credential.
 *
 * @property secureTokenStore Encrypted storage the token is written to and cleared from.
 * @property hubSpotRepository Performs the verification call.
 * @property settingsDataStore Records the resulting connection status.
 */
@Singleton
class ConnectHubSpotUseCase @Inject constructor(
    private val secureTokenStore: SecureTokenStore,
    private val hubSpotRepository: HubSpotRepository,
    private val settingsDataStore: SettingsDataStore,
) {

    /**
     * Saves the token, verifies it against HubSpot, and records the outcome.
     *
     * The token must be written **before** the test, because [HubSpotRepository.testConnection]
     * reads it from secure storage and accepts no token parameter. That makes the failure path
     * load-bearing: on failure the token is cleared again, so a mistyped token never silently
     * becomes the app's stored credential, and the status is reset to
     * [ConnectionStatus.SETUP_INCOMPLETE] because a 401 will have persisted an auth error that is
     * stale the instant the token is gone.
     *
     * @param token The pasted Private App access token; surrounding whitespace is trimmed.
     * @return [ConnectHubSpotResult.Success] when HubSpot answered, or
     *   [ConnectHubSpotResult.Failure] with the classified cause when it did not.
     */
    suspend operator fun invoke(token: String): ConnectHubSpotResult {
        secureTokenStore.saveAccessToken(token.trim())

        return when (val outcome = hubSpotRepository.testConnection()) {
            is ContactLookupOutcome.Failed -> {
                secureTokenStore.clearAccessToken()
                settingsDataStore.setHubSpotStatus(ConnectionStatus.SETUP_INCOMPLETE)
                val error = classify(outcome.reason)
                Log.w(TAG, "HubSpot connect rejected: $error")
                ConnectHubSpotResult.Failure(error)
            }

            else -> {
                settingsDataStore.setHubSpotStatus(ConnectionStatus.CONNECTED)
                Log.d(TAG, "HubSpot connect succeeded")
                ConnectHubSpotResult.Success
            }
        }
    }

    /**
     * Maps an error reason string returned by [HubSpotRepository] to a categorized
     * [HubSpotConnectError].
     *
     * A missing token classifies as [HubSpotConnectError.INVALID_TOKEN] rather than as its own
     * state: reaching here means a token was just written, so "no token" can only mean the
     * encrypted store rejected it, which the user resolves the same way — by entering it again.
     *
     * @param reason The internal failure reason string.
     * @return The classified error suitable for UI display.
     */
    fun classify(reason: String): HubSpotConnectError = when {
        reason == HubSpotRepositoryImpl.REASON_UNAUTHORIZED -> HubSpotConnectError.INVALID_TOKEN
        reason == HubSpotRepositoryImpl.REASON_NO_TOKEN -> HubSpotConnectError.INVALID_TOKEN
        reason.contains(FORBIDDEN_MARKER) -> HubSpotConnectError.MISSING_SCOPE
        else -> HubSpotConnectError.NETWORK
    }

    private companion object {
        /** Logging tag for this class. Never log the token itself. */
        const val TAG = "ConnectHubSpotUseCase"

        /** Substring of the repository's `http_403` reason, meaning a missing scope. */
        const val FORBIDDEN_MARKER = "403"
    }
}
