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

package com.digiroth.smsfilter.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MmsTextResolver] verifying safe execution against the device content resolver.
 *
 * Verifies that querying the Telephony MMS content provider executes without uncaught exceptions
 * when passed non-existent, null, or attachment-prefixed snippets on a live device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class MmsTextResolverTest {

    private lateinit var context: Context
    private lateinit var resolver: MmsTextResolver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resolver = MmsTextResolver(context)
    }

    /**
     * Tests that [MmsTextResolver] can be instantiated with an application context.
     *
     * Preconditions: ApplicationProvider provides application context.
     * Expected: [resolver] instance is non-null.
     */
    @Test
    fun mmsTextResolver_canBeInstantiated() {
        assertNotNull(resolver)
    }

    /**
     * Tests that querying for a non-existent snippet executes safely without throwing exceptions.
     *
     * Preconditions: Non-existent snippet string.
     * Expected: [MmsTextResolver.resolveFullMmsText] completes safely.
     */
    @Test
    fun resolveFullMmsText_handlesNonExistentSnippetSafely() {
        resolver.resolveFullMmsText("NonExistentSnippetThatCannotMatch12345")
    }

    /**
     * Tests that querying with a null snippet executes safely without throwing NullPointerException or SQL syntax errors.
     *
     * Preconditions: null snippet.
     * Expected: [MmsTextResolver.resolveFullMmsText] completes safely.
     */
    @Test
    fun resolveFullMmsText_handlesNullSnippetSafely() {
        resolver.resolveFullMmsText(null)
    }

    /**
     * Tests that querying with an attachment-prefixed snippet (e.g. "Image\nSTOP") executes safely against the provider.
     *
     * Preconditions: Snippet "Image\nSTOP".
     * Expected: [MmsTextResolver.resolveFullMmsText] sanitizes and queries safely.
     */
    @Test
    fun resolveFullMmsText_handlesImagePrefixedSnippetSafely() {
        resolver.resolveFullMmsText("Image\nSTOP")
    }
}
