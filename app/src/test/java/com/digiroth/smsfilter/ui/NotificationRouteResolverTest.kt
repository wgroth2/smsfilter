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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [NotificationRouteResolver].
 *
 * This mapping is where a real defect lived: routing a notification tap by *starting* the graph at
 * the requested screen left nothing beneath it, so Back exited the app instead of returning to the
 * dashboard. The fix moved the decision here, and these tests pin it without a device.
 */
class NotificationRouteResolverTest {

    /**
     * Tests that a detection notification opens the Activity destination.
     *
     * Preconditions: the launching intent carries [NotificationRoute.SCREEN_DETECTION_LOG].
     * Expected: [AppRoute.ACTIVITY] — a bottom-bar peer, reached by navigating on top of Status
     * rather than by replacing the start destination.
     */
    @Test
    fun `detection log request resolves to the activity destination`() {
        assertEquals(
            AppRoute.ACTIVITY,
            NotificationRouteResolver.resolve(NotificationRoute.SCREEN_DETECTION_LOG),
        )
    }

    /**
     * Tests that a settings request resolves to the pushed Settings destination.
     *
     * Preconditions: the launching intent carries [NotificationRoute.SCREEN_SETTINGS].
     * Expected: [AppRoute.SETTINGS]. The constant has no producer yet, so this pins the intended
     * mapping before one exists rather than after a notification silently goes nowhere.
     */
    @Test
    fun `settings request resolves to the settings destination`() {
        assertEquals(
            AppRoute.SETTINGS,
            NotificationRouteResolver.resolve(NotificationRoute.SCREEN_SETTINGS),
        )
    }

    /**
     * Tests that an ordinary launcher start requests nothing.
     *
     * Preconditions: no routing extra on the intent.
     * Expected: `null`, so the graph stays on its start destination and the app opens on Status.
     */
    @Test
    fun `absent request resolves to null`() {
        assertNull(NotificationRouteResolver.resolve(null))
    }

    /**
     * Tests that an unrecognised value is ignored rather than crashing or guessing.
     *
     * Preconditions: an extra value from a future or malformed notification.
     * Expected: `null`. A stale `PendingIntent` from an older install can carry a value this build
     * no longer knows, and opening the app normally is the right degradation.
     */
    @Test
    fun `unknown request resolves to null`() {
        assertNull(NotificationRouteResolver.resolve("some_removed_screen"))
        assertNull(NotificationRouteResolver.resolve(""))
    }
}
