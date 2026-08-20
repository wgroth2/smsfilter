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

import com.digiroth.smsfilter.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utilities for formatting application build metadata for display in the UI.
 */
object BuildInfo {

    /**
     * Formats an epoch millisecond timestamp into a human-readable build timestamp string,
     * including day, month, year, time down to the second, and timezone name.
     *
     * @param epochMillis The build timestamp in epoch milliseconds. Defaults to [BuildConfig.BUILD_TIME_EPOCH_MILLIS].
     * @param zoneId The timezone to format the timestamp in. Defaults to [ZoneId.systemDefault].
     * @param locale The locale to format with. Defaults to [Locale.getDefault].
     * @return A formatted string in the form `"Build: <date month year time timezone>"`.
     */
    fun formatBuildTime(
        epochMillis: Long = BuildConfig.BUILD_TIME_EPOCH_MILLIS,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        val formatter = DateTimeFormatter
            .ofPattern("d MMM yyyy, HH:mm:ss z", locale)
            .withZone(zoneId)
        return "Build: ${formatter.format(instant)}"
    }
}
