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

package com.digiroth.smsfilter.ui

import com.digiroth.smsfilter.platform.NotificationRoute

/**
 * Decides which destination a notification tap should open.
 *
 * Extracted from the navigation graph because this decision, not the rendering around it, is where
 * the routing bug lived: an earlier design started the graph *at* the requested screen, leaving
 * nothing beneath it, so `popUpTo` was a silent no-op and system Back exited the app. That is a
 * pure mapping question, and keeping it in a `LaunchedEffect` made it reachable only by launching
 * the app on a device.
 *
 * Returning `null` for an unrecognised or absent value is the ordinary launcher case: there is no
 * pending request, so the graph stays on its start destination.
 */
object NotificationRouteResolver {

    /**
     * Maps an `EXTRA_OPEN_SCREEN` value to the route to navigate to.
     *
     * @param requestedScreen The value of [NotificationRoute.EXTRA_OPEN_SCREEN] from the launching
     *   or most recent intent, or `null` for an ordinary launcher start.
     * @return The route to open, or `null` when nothing was requested.
     */
    fun resolve(requestedScreen: String?): String? = when (requestedScreen) {
        NotificationRoute.SCREEN_DETECTION_LOG -> AppRoute.ACTIVITY
        NotificationRoute.SCREEN_SETTINGS -> AppRoute.SETTINGS
        else -> null
    }
}
