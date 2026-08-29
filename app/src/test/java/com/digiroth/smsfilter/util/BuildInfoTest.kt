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

package com.digiroth.smsfilter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Unit tests for [BuildInfo] formatting.
 *
 * Verifies that build timestamp and version codes are formatted cleanly with appropriate
 * timezone designations and locale formatting.
 */
class BuildInfoTest {

    /**
     * Tests formatting of a known epoch timestamp in UTC with build number.
     *
     * Preconditions: Timestamp 2026-08-19T17:00:17Z, build number 40, UTC zone, US locale.
     * Expected: Formatted string equals "Build: 19 Aug 2026, 17:00:17 UTC (#40)".
     */
    @Test
    fun formatsBuildTimeCorrectlyWithTimezone() {
        val epochMillis = Instant.parse("2026-08-19T17:00:17Z").toEpochMilli()
        val utcZone = ZoneId.of("UTC")
        val usLocale = Locale.US

        val formatted = BuildInfo.formatBuildTime(
            epochMillis = epochMillis,
            buildNumber = 40,
            zoneId = utcZone,
            locale = usLocale,
        )

        assertEquals("Build: 19 Aug 2026, 17:00:17 UTC (#40)", formatted)
    }

    /**
     * Tests formatting of a known epoch timestamp in Pacific Daylight Time (PDT) with build number.
     *
     * Preconditions: Timestamp 2026-08-19T17:00:17Z, build number 42, America/Los_Angeles zone, US locale.
     * Expected: Formatted string equals "Build: 19 Aug 2026, 10:00:17 PDT (#42)".
     */
    @Test
    fun formatsBuildTimeWithPacificTimezone() {
        val epochMillis = Instant.parse("2026-08-19T17:00:17Z").toEpochMilli()
        val pacificZone = ZoneId.of("America/Los_Angeles")
        val usLocale = Locale.US

        val formatted = BuildInfo.formatBuildTime(
            epochMillis = epochMillis,
            buildNumber = 42,
            zoneId = pacificZone,
            locale = usLocale,
        )

        assertEquals("Build: 19 Aug 2026, 10:00:17 PDT (#42)", formatted)
    }

    /**
     * Tests that the default formatBuildTime overload produces a string with the expected prefix and build number pattern.
     *
     * Preconditions: Calling formatBuildTime with default arguments.
     * Expected: Result starts with "Build: " and contains "(#".
     */
    @Test
    fun defaultBuildTimeContainsPrefixAndYear() {
        val formatted = BuildInfo.formatBuildTime()
        assertTrue("Must start with 'Build: '", formatted.startsWith("Build: "))
        assertTrue("Must contain build number", formatted.contains("(#"))
    }
}
