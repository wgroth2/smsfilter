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

package com.digiroth.smsfilter.util

import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The kind of address an SMS arrived from, which determines whether it can be normalized to
 * E.164 and whether it can receive a reply at all.
 */
enum class SenderClass {
    /**
     * An ordinary dialable number, with or without a country code. Eligible for E.164
     * normalization and for replies.
     */
    STANDARD,

    /**
     * A 5–6 digit short code — the most common source of marketing and opt-out SMS. E.164
     * conversion is skipped entirely: lookups and replies use the raw digits exactly as
     * received, because a short code must be replied to at the precise address it sent from.
     */
    SHORT_CODE,

    /**
     * An address that cannot receive an SMS reply. Covers alphanumeric sender IDs such as
     * "VERIZON", and also degenerate addresses containing no digits at all (blank or
     * punctuation-only), since those are equally undialable. Detection still runs on the
     * message; only the auto-reply is skipped.
     */
    ALPHANUMERIC,
}

/**
 * The result of inspecting one originating address.
 *
 * @property rawAddress The originating address exactly as received. Replies must always be
 *   addressed to this value, never to [e164].
 * @property senderClass How the address was classified.
 * @property e164 The E.164 form, or `null` if this sender class skips normalization or
 *   normalization failed. A `null` here is not an error — callers fall back to [rawAddress].
 * @property digits Every digit in the address, with punctuation and spacing removed. Used as
 *   the raw-digits fallback for a HubSpot search when an E.164 search returns no match.
 */
data class NormalizedSender(
    val rawAddress: String,
    val senderClass: SenderClass,
    val e164: String?,
    val digits: String,
) {
    /**
     * Whether an SMS reply can be delivered to this sender. `false` for [SenderClass.ALPHANUMERIC],
     * which is gate 2 of the auto-reply safety controls.
     */
    val isRepliable: Boolean
        get() = senderClass != SenderClass.ALPHANUMERIC

    /**
     * The value to search contact sources with first: the normalized form when one exists,
     * otherwise the raw address unchanged.
     */
    val primaryLookupValue: String
        get() = e164 ?: rawAddress
}

/**
 * Classifies incoming sender addresses and normalizes dialable ones to E.164.
 *
 * Classification is pure Kotlin with no dependency on the Android framework, so it is directly
 * unit-testable; the single platform-dependent step is delegated to [E164Formatter]. See that
 * interface for why the seam exists.
 *
 * No external phone-number library is used, and none may be added — the platform's own
 * `PhoneNumberUtils` is the only conversion permitted by the specification.
 *
 * @property formatter Performs the E.164 conversion. Defaults to [PlatformE164Formatter] in
 *   production; tests substitute a fake.
 */
@Singleton
class PhoneNumberNormalizer @VisibleForTesting constructor(
    private val formatter: E164Formatter,
) {

    /** Production constructor used by Hilt; wires in the platform formatter. */
    @Inject
    constructor() : this(PlatformE164Formatter)

    /**
     * Classifies an originating address without attempting any conversion.
     *
     * Pure function — safe to call from a JVM unit test and from any thread.
     *
     * @param address The originating address exactly as received.
     * @return The [SenderClass] of the address.
     */
    fun classify(address: String): SenderClass {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return SenderClass.ALPHANUMERIC

        // Any letter makes the address an alphanumeric sender ID, which cannot receive replies.
        if (trimmed.any(Char::isLetter)) return SenderClass.ALPHANUMERIC

        val digits = trimmed.filter(Char::isDigit)
        // No digits at all (punctuation or symbols only) is equally undialable.
        if (digits.isEmpty()) return SenderClass.ALPHANUMERIC

        // A leading '+' signals international dialing, so a short digit count after one is a
        // (malformed) international number rather than a short code.
        val isShortCode = !trimmed.startsWith(INTERNATIONAL_PREFIX) &&
            digits.length in SHORT_CODE_MIN_DIGITS..SHORT_CODE_MAX_DIGITS

        return if (isShortCode) SenderClass.SHORT_CODE else SenderClass.STANDARD
    }

    /**
     * Normalizes an address to E.164, if that is meaningful for its sender class.
     *
     * @param address The originating address exactly as received.
     * @return The E.164 form, or `null` when the sender is a short code or alphanumeric ID
     *   (where conversion is deliberately skipped), or when the platform could not parse the
     *   number. Callers must treat `null` as "use the raw address unchanged", not as an error.
     */
    fun normalizeToE164(address: String): String? = when (classify(address)) {
        // Short codes are looked up and replied to using their raw digits; converting them
        // would produce a wrong address or nothing at all.
        SenderClass.SHORT_CODE -> null
        SenderClass.ALPHANUMERIC -> null
        SenderClass.STANDARD -> formatter.format(address, DEFAULT_REGION)?.takeIf(String::isNotBlank)
    }

    /**
     * Performs the full inspection of an address in one pass: classification, normalization,
     * and digit extraction.
     *
     * @param address The originating address exactly as received.
     * @return A [NormalizedSender] describing the address.
     */
    fun normalize(address: String): NormalizedSender {
        val senderClass = classify(address)
        val e164 = when (senderClass) {
            SenderClass.SHORT_CODE, SenderClass.ALPHANUMERIC -> null
            SenderClass.STANDARD ->
                formatter.format(address, DEFAULT_REGION)?.takeIf(String::isNotBlank)
        }
        return NormalizedSender(
            rawAddress = address,
            senderClass = senderClass,
            e164 = e164,
            digits = address.filter(Char::isDigit),
        )
    }

    /**
     * Whether a reply can be delivered to this address.
     *
     * @param address The originating address exactly as received.
     * @return `false` for alphanumeric sender IDs and undialable addresses.
     */
    fun isRepliable(address: String): Boolean = classify(address) != SenderClass.ALPHANUMERIC

    companion object {
        /** Default region used when an address carries no country code. */
        const val DEFAULT_REGION: String = "US"

        /** Fewest digits an address may have and still be treated as a short code. */
        const val SHORT_CODE_MIN_DIGITS: Int = 5

        /** Most digits an address may have and still be treated as a short code. */
        const val SHORT_CODE_MAX_DIGITS: Int = 6

        /** Prefix marking an address as already carrying an international country code. */
        private const val INTERNATIONAL_PREFIX: String = "+"
    }
}
