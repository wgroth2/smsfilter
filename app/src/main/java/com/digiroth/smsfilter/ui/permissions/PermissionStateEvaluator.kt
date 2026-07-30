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

package com.digiroth.smsfilter.ui.permissions

import javax.inject.Inject

/**
 * What the UI should offer the user for one runtime permission.
 */
sealed interface PermissionState {

    /**
     * The permission has never been requested. Every fresh install starts here, and the UI must
     * show a plain request button rather than any denial messaging.
     */
    data object NotRequested : PermissionState

    /** The permission is held. */
    data object Granted : PermissionState

    /**
     * Denied, but Android will still show the system dialog, so asking again is worthwhile — usually
     * paired with a rationale explaining why the app needs it.
     */
    data object DeniedCanRetry : PermissionState

    /**
     * Denied and Android will no longer show the system dialog. The only remaining route is the
     * App Settings screen, so the UI must switch from a request button to an "Open App Settings"
     * button; leaving a request button here produces a control that silently does nothing.
     */
    data object DeniedPermanently : PermissionState
}

/**
 * Decides which [PermissionState] a permission is in.
 *
 * This exists as its own pure class for one reason: `shouldShowRequestPermissionRationale` returns
 * `false` in two completely different situations — when a permission has been permanently denied,
 * **and** when it has never been requested at all. Reading that flag alone therefore tells every
 * first-time user, before they have seen a single system dialog, that they have blocked the
 * permission and must go to App Settings.
 *
 * Distinguishing the two requires a third input the platform does not provide: whether a request
 * has actually completed. That makes this a small piece of genuine state-machine logic rather than
 * a passthrough, and worth testing directly — which is why it carries no `android.*` imports.
 */
class PermissionStateEvaluator @Inject constructor() {

    /**
     * Evaluates one permission.
     *
     * @param isGranted Whether the permission is currently held.
     * @param hasBeenRequested Whether a request for this permission has completed in this session.
     *   The caller tracks this; the platform does not expose it.
     * @param shouldShowRationale The value of `ActivityCompat.shouldShowRequestPermissionRationale`
     *   for this permission. Meaningful only once [hasBeenRequested] is `true`.
     * @return The state the UI should render.
     */
    fun evaluate(
        isGranted: Boolean,
        hasBeenRequested: Boolean,
        shouldShowRationale: Boolean,
    ): PermissionState = when {
        isGranted -> PermissionState.Granted

        // Checked before the rationale flag on purpose. On a fresh install the flag is false, which
        // is indistinguishable from a permanent denial without this guard.
        !hasBeenRequested -> PermissionState.NotRequested

        shouldShowRationale -> PermissionState.DeniedCanRetry

        else -> PermissionState.DeniedPermanently
    }

    /**
     * Whether a state permits advancing past a blocking permission.
     *
     * @param state The evaluated state.
     * @return `true` only when the permission is held.
     */
    fun allowsAdvance(state: PermissionState): Boolean = state == PermissionState.Granted
}
