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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TopLevelDestination].
 *
 * [TopLevelDestination.isTopLevel] decides whether the bottom navigation bar is on screen. Its
 * negative cases carry the weight: the bar must be absent during onboarding, because the wizard is
 * the only writer of `firstRunComplete` — the flag gating the SMS pipeline — and a bar that let the
 * user navigate away mid-setup would leave the filter switched off while the app looked ready.
 */
class TopLevelDestinationTest {

    /**
     * Tests that the bar carries exactly the three peer destinations, in order.
     *
     * Preconditions: none.
     * Expected: Status, Activity, Rules. Settings is deliberately absent — it is pushed from the
     * Status app bar, which is both the platform convention and what keeps the bar within the
     * 3–5 destinations Material 3 specifies.
     */
    @Test
    fun `bar carries three peers in order`() {
        assertEquals(
            listOf(AppRoute.STATUS, AppRoute.ACTIVITY, AppRoute.RULES),
            TopLevelDestination.entries.map { it.route },
        )
    }

    /**
     * Tests that each peer route shows the bar.
     *
     * Preconditions: none.
     * Expected: every declared route is recognised as top-level.
     */
    @Test
    fun `every peer route shows the bar`() {
        TopLevelDestination.entries.forEach { destination ->
            assertTrue(destination.route, TopLevelDestination.isTopLevel(destination.route))
        }
    }

    /**
     * Tests that onboarding never shows the bar.
     *
     * Preconditions: the current route is the wizard.
     * Expected: `false`. This is the load-bearing case — see the class documentation.
     */
    @Test
    fun `onboarding never shows the bar`() {
        assertFalse(TopLevelDestination.isTopLevel(AppRoute.ONBOARDING))
    }

    /**
     * Tests that the pushed Settings screen does not show the bar.
     *
     * Preconditions: the current route is Settings.
     * Expected: `false`. Settings is a detail screen with a Back arrow, not a peer.
     */
    @Test
    fun `pushed settings does not show the bar`() {
        assertFalse(TopLevelDestination.isTopLevel(AppRoute.SETTINGS))
    }

    /**
     * Tests that an unresolved route does not show the bar.
     *
     * Preconditions: the destination is `null`, which happens on the first frame before the
     * navigation graph resolves.
     * Expected: `false`, so the bar fades in with its destination rather than appearing over the
     * loading state.
     */
    @Test
    fun `null route does not show the bar`() {
        assertFalse(TopLevelDestination.isTopLevel(null))
    }
}
