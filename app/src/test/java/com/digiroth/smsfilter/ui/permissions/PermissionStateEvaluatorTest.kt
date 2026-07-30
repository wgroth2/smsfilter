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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PermissionStateEvaluator].
 *
 * The case that matters most is `freshInstall...`: `shouldShowRequestPermissionRationale` is `false`
 * on a fresh install exactly as it is after a permanent denial, so an evaluator that trusts that
 * flag alone would tell every first-time user they had blocked the permission. That failure is
 * invisible to a compile check and only reproducible by uninstalling the app, which makes it worth
 * pinning here.
 */
class PermissionStateEvaluatorTest {

    private val evaluator = PermissionStateEvaluator()

    @Test
    fun `granted permission is Granted`() {
        val state = evaluator.evaluate(
            isGranted = true,
            hasBeenRequested = true,
            shouldShowRationale = false,
        )

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    fun `granted permission is Granted even before any request`() {
        // Possible when the permission was granted in a previous install or by the system.
        val state = evaluator.evaluate(
            isGranted = true,
            hasBeenRequested = false,
            shouldShowRationale = false,
        )

        assertEquals(PermissionState.Granted, state)
    }

    @Test
    fun `fresh install with no rationale is NotRequested not DeniedPermanently`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = false,
            shouldShowRationale = false,
        )

        assertEquals(
            "a first-time user must never be shown the permanently-denied escape hatch",
            PermissionState.NotRequested,
            state,
        )
    }

    @Test
    fun `not yet requested is NotRequested regardless of the rationale flag`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = false,
            shouldShowRationale = true,
        )

        assertEquals(PermissionState.NotRequested, state)
    }

    @Test
    fun `denied once with rationale available can be retried`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = true,
            shouldShowRationale = true,
        )

        assertEquals(PermissionState.DeniedCanRetry, state)
    }

    @Test
    fun `denied with no rationale available is DeniedPermanently`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = true,
            shouldShowRationale = false,
        )

        assertEquals(PermissionState.DeniedPermanently, state)
    }

    @Test
    fun `only Granted allows advancing past a blocking permission`() {
        assertTrue(evaluator.allowsAdvance(PermissionState.Granted))
        assertFalse(evaluator.allowsAdvance(PermissionState.NotRequested))
        assertFalse(evaluator.allowsAdvance(PermissionState.DeniedCanRetry))
        assertFalse(evaluator.allowsAdvance(PermissionState.DeniedPermanently))
    }

    @Test
    fun `every input combination yields exactly one state`() {
        // Guards against a future edit leaving a gap in the when-expression.
        val states = listOf(true, false).flatMap { granted ->
            listOf(true, false).flatMap { requested ->
                listOf(true, false).map { rationale ->
                    evaluator.evaluate(granted, requested, rationale)
                }
            }
        }

        assertEquals(8, states.size)
        assertEquals(
            "all four states must be reachable",
            setOf(
                PermissionState.Granted,
                PermissionState.NotRequested,
                PermissionState.DeniedCanRetry,
                PermissionState.DeniedPermanently,
            ),
            states.toSet(),
        )
    }
}
