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

package com.digiroth.smsfilter.data.repository

/**
 * The result of asking one contact source whether a sender is known.
 *
 * [Failed] is a distinct state rather than being folded into [NotFound] on purpose. The
 * specification requires that a lookup which fails for network or API reasons still allows the
 * message to be processed for opt-outs — erring on the side of acting. Collapsing failure into
 * "not found" would reach the same next step today but would lose the distinction the log and
 * the connection-health indicator need, and would make an outage indistinguishable from a
 * genuine miss.
 */
sealed interface ContactLookupOutcome {

    /** The sender is a known contact; the message must be ignored. */
    data object Found : ContactLookupOutcome

    /** The sender is definitively not a known contact in this source. */
    data object NotFound : ContactLookupOutcome

    /**
     * The lookup could not be completed.
     *
     * @property reason Short human-readable cause, for logging and the health indicator.
     */
    data class Failed(val reason: String) : ContactLookupOutcome
}
