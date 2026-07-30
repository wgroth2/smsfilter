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
import javax.inject.Inject

/** How the HubSpot indicator should render in the Connection Health Summary. */
enum class HubSpotHealth {
    /** Grey. The user has switched HubSpot off; this is a choice, never an error. */
    OFF,

    /** Amber. Enabled but unusable — the toggle is on and no token has been saved yet. */
    SETUP_INCOMPLETE,

    /** Green. Enabled, a token is saved, and the last check succeeded. */
    CONNECTED,

    /** Red. Enabled with a saved token, but the last call failed or was rejected. */
    ERROR,
}

/** How the Google Contacts indicator should render. */
enum class GoogleContactsHealth {
    /** Green. The permission is held and lookups will work. */
    CONNECTED,

    /** Red. The permission is missing, so every sender is treated as unknown. */
    PERMISSION_REQUIRED,
}

/**
 * Derives the Connection Health Summary indicators.
 *
 * Kept as its own pure class because the HubSpot side has four states rather than the obvious
 * three, and two of them must never be styled as errors: a deliberately disabled integration, and
 * one that is enabled but not yet configured. The amber state is reachable in ordinary use, since
 * the post-onboarding dialog's "Connect" button turns the toggle on before any token exists.
 */
class ConnectionHealthEvaluator @Inject constructor() {

    /**
     * Evaluates the HubSpot indicator.
     *
     * Order matters. [hasToken] is checked before [lastStatus] so that a stale failure status left
     * over from a rejected token cannot show red once that token has been cleared — the honest state
     * then is "not configured", not "broken".
     *
     * A saved token implies a previously successful check, because the connect flow only persists a
     * token after [ConnectionStatus.CONNECTED] is observed. That is why an unknown status with a
     * saved token resolves to [HubSpotHealth.CONNECTED] rather than to a fourth "unverified" state.
     *
     * @param isEnabled The "Use HubSpot" toggle.
     * @param hasToken Whether a Private App token is stored.
     * @param lastStatus The persisted result of the most recent HubSpot call.
     * @return The indicator to render.
     */
    fun evaluateHubSpot(
        isEnabled: Boolean,
        hasToken: Boolean,
        lastStatus: ConnectionStatus,
    ): HubSpotHealth = when {
        !isEnabled -> HubSpotHealth.OFF
        !hasToken -> HubSpotHealth.SETUP_INCOMPLETE
        lastStatus == ConnectionStatus.AUTH_ERROR -> HubSpotHealth.ERROR
        lastStatus == ConnectionStatus.DISCONNECTED -> HubSpotHealth.ERROR
        else -> HubSpotHealth.CONNECTED
    }

    /**
     * Evaluates the Google Contacts indicator.
     *
     * Driven by the live permission rather than the persisted status: the user can revoke contacts
     * access from system settings at any time, and the indicator must reflect that immediately
     * rather than waiting for the next lookup to record a failure.
     *
     * @param hasPermission Whether `READ_CONTACTS` is currently granted.
     * @return The indicator to render.
     */
    fun evaluateGoogleContacts(hasPermission: Boolean): GoogleContactsHealth =
        if (hasPermission) GoogleContactsHealth.CONNECTED else GoogleContactsHealth.PERMISSION_REQUIRED
}
