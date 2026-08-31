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

package com.digiroth.smsfilter.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [NotificationBodyAssembler].
 *
 * The assembler exists because the previous behaviour — join every fragment, take the longest
 * candidate — handed the pipeline a body that was the whole visible conversation rather than the
 * message that had just arrived. These tests pin the newest-incoming-fragment rule and the one
 * narrow exception to it, the truncation recovery.
 */
class NotificationBodyAssemblerTest {

    private val assembler = NotificationBodyAssembler()

    /**
     * Helper building an incoming fragment.
     *
     * @param text The message text.
     * @return A fragment attributed to the other party.
     */
    private fun incoming(text: String): NotificationFragment =
        NotificationFragment(text = text, isFromSelf = false)

    /**
     * Helper building a fragment the user wrote.
     *
     * @param text The message text.
     * @return A fragment attributed to the user.
     */
    private fun outgoing(text: String): NotificationFragment =
        NotificationFragment(text = text, isFromSelf = true)

    /**
     * Tests that only the newest incoming fragment becomes the body, never the joined history.
     *
     * This is the core regression guard. Under the previous behaviour the body was
     * "Hi there\nFlash sale today", which let a keyword in the older message decide the fate of
     * the newer one.
     *
     * Preconditions: Two incoming fragments, oldest first.
     * Expected: Body is the second fragment alone.
     */
    @Test
    fun assemble_returnsOnlyTheNewestIncomingFragment() {
        val body = assembler.assemble(
            fragments = listOf(incoming("Hi there"), incoming("Flash sale today")),
            bigText = null,
            text = null,
            textLines = null,
        )
        assertEquals("Flash sale today", body)
    }

    /**
     * Tests that fragments the user wrote are never treated as the incoming message.
     *
     * MessagingStyle carries outgoing messages alongside incoming ones. Evaluating the user's own
     * words for opt-out patterns would auto-reply to a conversation nobody asked to leave.
     *
     * Preconditions: The newest fragment is outgoing; an older incoming fragment precedes it.
     * Expected: Body is the incoming fragment.
     */
    @Test
    fun assemble_ignoresFragmentsTheUserWrote() {
        val body = assembler.assemble(
            fragments = listOf(incoming("Flash sale today"), outgoing("please stop texting me")),
            bigText = null,
            text = null,
            textLines = null,
        )
        assertEquals("Flash sale today", body)
    }

    /**
     * Tests that the body falls back to EXTRA_BIG_TEXT when the notification carries no fragments.
     *
     * Preconditions: No fragments; bigText and text both present.
     * Expected: Body is the longer of the extras.
     */
    @Test
    fun assemble_fallsBackToBigTextWithoutFragments() {
        val body = assembler.assemble(
            fragments = emptyList(),
            bigText = "Full marketing message with the whole body present",
            text = "Full marketing message",
            textLines = null,
        )
        assertEquals("Full marketing message with the whole body present", body)
    }

    /**
     * Tests that the body falls back to EXTRA_TEXT when no fragments and no big text exist.
     *
     * Preconditions: Only EXTRA_TEXT is populated.
     * Expected: Body is that value.
     */
    @Test
    fun assemble_fallsBackToTextWhenNoBigTextExists() {
        val body = assembler.assemble(
            fragments = emptyList(),
            bigText = null,
            text = "Short notification body",
            textLines = null,
        )
        assertEquals("Short notification body", body)
    }

    /**
     * Tests that a truncated fragment is replaced by an extra that demonstrably continues it.
     *
     * This is the trade-off the newest-fragment rule has to pay for: dropping the join gives up the
     * accidental recovery of elided text, so the recovery is made explicit here.
     *
     * Preconditions: Newest fragment ends in an ellipsis; bigText starts with the same stem and is
     * longer.
     * Expected: Body is the full bigText.
     */
    @Test
    fun assemble_recoversFullTextWhenTheNewestFragmentIsTruncated() {
        val body = assembler.assemble(
            fragments = listOf(incoming("Flash sale ends tonight. Reply…")),
            bigText = "Flash sale ends tonight. Reply STOP to unsubscribe",
            text = null,
            textLines = null,
        )
        assertEquals("Flash sale ends tonight. Reply STOP to unsubscribe", body)
    }

    /**
     * Tests that a longer extra unrelated to the truncated fragment does not displace it.
     *
     * Preference by length alone is what allowed an arbitrary string to become the body. A
     * candidate is only accepted when it continues the same message.
     *
     * Preconditions: Newest fragment is truncated; bigText is longer but starts differently.
     * Expected: Body remains the truncated fragment.
     */
    @Test
    fun assemble_keepsTruncatedFragmentWhenNoExtraContinuesIt() {
        val body = assembler.assemble(
            fragments = listOf(incoming("Flash sale ends tonight. Reply…")),
            bigText = "3 new messages in this conversation, tap to open and read them all",
            text = null,
            textLines = null,
        )
        assertEquals("Flash sale ends tonight. Reply…", body)
    }

    /**
     * Tests that a group conversation's fragments are not merged into one body.
     *
     * Preconditions: Fragments from two different participants.
     * Expected: Body is the newest fragment alone, not both concatenated.
     */
    @Test
    fun assemble_doesNotMergeFragmentsFromDifferentParticipants() {
        val body = assembler.assemble(
            fragments = listOf(incoming("Alice: are we still on?"), incoming("Bob: yes see you then")),
            bigText = null,
            text = null,
            textLines = null,
        )
        assertEquals("Bob: yes see you then", body)
    }

    /**
     * Tests that a notification with nothing usable resolves to null.
     *
     * Preconditions: No fragments and every extra blank or absent.
     * Expected: Returns null.
     */
    @Test
    fun assemble_returnsNullWhenNothingUsableIsPresent() {
        assertNull(
            assembler.assemble(fragments = emptyList(), bigText = "   ", text = null, textLines = null),
        )
    }
}
