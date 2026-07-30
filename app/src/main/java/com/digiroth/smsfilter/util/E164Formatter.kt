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

import android.telephony.PhoneNumberUtils
import android.util.Log

/**
 * Converts a raw dialable string to E.164 form.
 *
 * This interface exists purely as a seam around the Android platform call. In a JVM unit test
 * the framework is replaced by a stub whose methods throw
 * `Method … in android.telephony.PhoneNumberUtils not mocked`, so a class that calls
 * `PhoneNumberUtils` directly cannot be unit-tested. Isolating the one platform-dependent
 * operation here lets [PhoneNumberNormalizer]'s classification and fallback logic — the parts
 * that actually carry risk — be tested on the JVM with a fake, while production still uses the
 * platform implementation.
 */
fun interface E164Formatter {

    /**
     * Formats a raw number as E.164.
     *
     * @param rawNumber The originating address exactly as received.
     * @param defaultRegion ISO 3166-1 alpha-2 region code used when [rawNumber] carries no
     *   country code, e.g. `"US"`.
     * @return The E.164 representation, or `null` if the input cannot be interpreted as a
     *   dialable number in that region.
     */
    fun format(rawNumber: String, defaultRegion: String): String?
}

/**
 * The production [E164Formatter], delegating to the platform's own implementation.
 *
 * The specification forbids adding an external phone-number library, so
 * `PhoneNumberUtils.formatNumberToE164` is the only conversion used. It returns `null` for
 * input it cannot parse, and the call is additionally wrapped so that an unexpected throw on a
 * malformed address degrades to "normalization failed" rather than propagating into the SMS
 * pipeline.
 */
object PlatformE164Formatter : E164Formatter {

    private const val TAG = "PlatformE164Formatter"

    override fun format(rawNumber: String, defaultRegion: String): String? = runCatching {
        PhoneNumberUtils.formatNumberToE164(rawNumber, defaultRegion)
    }.onFailure { error ->
        Log.w(TAG, "E.164 formatting threw for a sender address; treating as unnormalizable", error)
    }.getOrNull()
}
