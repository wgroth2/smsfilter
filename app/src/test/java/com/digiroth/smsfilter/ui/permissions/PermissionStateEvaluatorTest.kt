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

package com.digiroth.smsfilter.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PermissionStateEvaluator].
 *
 * Verifies permission state evaluation across all combinations of runtime grant status,
 * previous request history, and system rationale visibility.
 */
class PermissionStateEvaluatorTest {

    private val evaluator = PermissionStateEvaluator()

    /**
     * Tests that an already-granted permission is evaluated as Granted.
     *
     * Preconditions: isGranted=true, hasBeenRequested=true, shouldShowRationale=false.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.Granted].
     */
    @Test
    fun `granted permission is Granted`() {
        val state = evaluator.evaluate(
            isGranted = true,
            hasBeenRequested = true,
            shouldShowRationale = false,
        )

        assertEquals(PermissionState.Granted, state)
    }

    /**
     * Tests that a granted permission evaluates to Granted even if the app has not explicitly prompted yet in this session.
     *
     * Preconditions: isGranted=true, hasBeenRequested=false, shouldShowRationale=false.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.Granted].
     */
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

    /**
     * Tests that a fresh install without prior requests is evaluated as NotRequested rather than DeniedPermanently.
     *
     * Preconditions: isGranted=false, hasBeenRequested=false, shouldShowRationale=false.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.NotRequested].
     */
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

    /**
     * Tests that an unrequested permission remains NotRequested regardless of the rationale flag value.
     *
     * Preconditions: isGranted=false, hasBeenRequested=false, shouldShowRationale=true.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.NotRequested].
     */
    @Test
    fun `not yet requested is NotRequested regardless of the rationale flag`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = false,
            shouldShowRationale = true,
        )

        assertEquals(PermissionState.NotRequested, state)
    }

    /**
     * Tests that a permission denied with rationale available evaluates to DeniedCanRetry.
     *
     * Preconditions: isGranted=false, hasBeenRequested=true, shouldShowRationale=true.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.DeniedCanRetry].
     */
    @Test
    fun `denied once with rationale available can be retried`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = true,
            shouldShowRationale = true,
        )

        assertEquals(PermissionState.DeniedCanRetry, state)
    }

    /**
     * Tests that a previously requested permission denied with no rationale available evaluates to DeniedPermanently.
     *
     * Preconditions: isGranted=false, hasBeenRequested=true, shouldShowRationale=false.
     * Expected: [PermissionStateEvaluator.evaluate] returns [PermissionState.DeniedPermanently].
     */
    @Test
    fun `denied with no rationale available is DeniedPermanently`() {
        val state = evaluator.evaluate(
            isGranted = false,
            hasBeenRequested = true,
            shouldShowRationale = false,
        )

        assertEquals(PermissionState.DeniedPermanently, state)
    }

    /**
     * Tests that only the Granted state allows advancing through permission gates.
     *
     * Preconditions: Evaluating allowsAdvance for Granted, NotRequested, DeniedCanRetry, and DeniedPermanently.
     * Expected: Returns true only for [PermissionState.Granted] and false for all other states.
     */
    @Test
    fun `only Granted allows advancing past a blocking permission`() {
        assertTrue(evaluator.allowsAdvance(PermissionState.Granted))
        assertFalse(evaluator.allowsAdvance(PermissionState.NotRequested))
        assertFalse(evaluator.allowsAdvance(PermissionState.DeniedCanRetry))
        assertFalse(evaluator.allowsAdvance(PermissionState.DeniedPermanently))
    }

    /**
     * Tests that all 8 boolean permutations produce one of the four defined permission states.
     *
     * Preconditions: Iterating through all permutations of isGranted, hasBeenRequested, and shouldShowRationale.
     * Expected: Total 8 evaluations covering all 4 enum values without exceptions or unhandled states.
     */
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
