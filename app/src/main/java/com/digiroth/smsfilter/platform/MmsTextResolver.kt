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
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves full text bodies of MMS messages directly from the Android Telephony content provider.
 *
 * For official Android documentation on the Telephony MMS content provider, see:
 * - Telephony.Mms: [https://developer.android.com/reference/android/provider/Telephony.Mms](https://developer.android.com/reference/android/provider/Telephony.Mms)
 *
 * Notification payloads from messaging apps (e.g. Google Messages) often truncate MMS body text
 * or prepend image/attachment placeholders such as "Image\n". This resolver queries the telephony
 * MMS part table (`content://mms/part`) to locate the complete plain text part associated with recent
 * incoming MMS messages.
 *
 * @property context The application context used to obtain the content resolver.
 */
@Singleton
open class MmsTextResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Resolves the full text body of an MMS message, retrying asynchronously if the telephony
     * provider has not yet committed the text part records.
     *
     * Performs an initial immediate lookup via [resolveFullMmsText]. If no matching text is found
     * (e.g. if the notification arrived before the MMS part was fully parsed and saved to telephony storage),
     * suspends for [delayMillis] and retries up to [maxAttempts] total attempts.
     *
     * @param prefixSnippet The initial snippet or truncated notification body to match against, or `null`.
     * @param maxAttempts Maximum number of query attempts before giving up. Defaults to 6.
     * @param delayMillis Suspension duration between retry attempts in milliseconds. Defaults to 750ms.
     * @return The resolved full MMS body text, or `null` if resolution failed across all attempts.
     */
    suspend fun resolveFullMmsTextWithRetry(
        prefixSnippet: String? = null,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        delayMillis: Long = DEFAULT_DELAY_MILLIS,
    ): String? {
        for (attempt in 1..maxAttempts) {
            val resolved = resolveFullMmsText(prefixSnippet)
            if (resolved != null) {
                return resolved
            }
            if (attempt < maxAttempts) {
                delay(delayMillis)
            }
        }
        return null
    }

    /**
     * Resolves the full text body of a recently received MMS message from the telephony provider.
     *
     * Queries `content://mms/part` for parts with content type `text/plain`, sorted by `_id DESC`,
     * collecting up to the newest [MAX_RECORDS_TO_CHECK] records and handing them to
     * [selectMatchingPart], which owns the decision of which one the notification is about.
     *
     * A `null` or blank [prefixSnippet] resolves to `null`. There is deliberately **no** "return
     * the newest part" fallback: the query above filters on content type alone, so it spans every
     * conversation in the MMS store in both directions, and the newest part bears no necessary
     * relation to the message being resolved. Returning it allowed an unrelated message — including
     * one the user themselves sent — to be processed as the incoming body and auto-replied to.
     * Resolving nothing is the safe failure, because the caller then keeps the notification's own
     * text.
     *
     * @param prefixSnippet The initial snippet or truncated notification body to match against, or `null`.
     * @return The full MMS message text body if resolved, or `null` if not found or on error.
     */
    open fun resolveFullMmsText(prefixSnippet: String? = null): String? = runCatching {
        val uri = MMS_PART_URI
        val projection = arrayOf(COLUMN_ID, COLUMN_TEXT)
        val selection = "$COLUMN_CONTENT_TYPE = ?"
        val selectionArgs = arrayOf(MIME_TYPE_TEXT_PLAIN)
        val sortOrder = "$COLUMN_ID DESC"

        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idColumnIndex = cursor.getColumnIndex(COLUMN_ID)
            val textColumnIndex = cursor.getColumnIndex(COLUMN_TEXT)
            if (textColumnIndex == -1) {
                Log.w(TAG, "MMS part cursor missing '$COLUMN_TEXT' column")
                return@runCatching null
            }

            val partTexts = mutableListOf<String>()
            var recordsChecked = 0

            while (cursor.moveToNext() && (recordsChecked < MAX_RECORDS_TO_CHECK)) {
                recordsChecked++
                var text = cursor.getString(textColumnIndex)

                // If the text column is null or empty, attempt to read from the part stream
                if (text.isNullOrBlank() && (idColumnIndex != -1)) {
                    val partId = cursor.getLong(idColumnIndex)
                    text = readTextFromPartStream(partId)
                }

                if (!text.isNullOrBlank()) {
                    partTexts.add(text)
                }
            }

            selectMatchingPart(partTexts, prefixSnippet)?.also {
                Log.d(TAG, "Matched full MMS text ($recordsChecked records examined)")
            }
        }
    }.onFailure { error ->
        Log.e(TAG, "Failed to resolve full MMS text from telephony provider", error)
    }.getOrNull()

    /**
     * Chooses which candidate MMS text part corresponds to [prefixSnippet].
     *
     * Pure by design — no `android.*` types and no I/O — so the rule that decides which stored
     * text a notification is actually about can be unit-tested on the JVM. [resolveFullMmsText]
     * supplies the candidates it read from the telephony provider; this function makes the choice.
     *
     * A snippet that carries no usable text after sanitizing returns `null` rather than falling back
     * to the newest part. That fallback used to exist and was the cause of a real defect: a
     * caption-less "Image" notification sanitizes to blank, so every such message resolved to
     * whatever text part happened to be newest on the device, which could belong to any
     * conversation. See [resolveFullMmsText].
     *
     * @param partTexts Candidate part bodies in newest-first order, already stripped of blanks.
     * @param prefixSnippet The notification snippet to match against, or `null`.
     * @return The first part matching the snippet, or `null` when nothing matches or the snippet
     *   carries no usable text.
     */
    internal fun selectMatchingPart(partTexts: List<String>, prefixSnippet: String?): String? {
        val cleanSnippet = prefixSnippet
            ?.let(::sanitizeSnippet)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val searchPrefix = cleanSnippet
            .take(SEARCH_PREFIX_LENGTH)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        // Non-breaking spaces are common in marketing MMS and do not survive the notification
        // round-trip identically, so both sides are normalized before any comparison.
        val normalizedPrefix = searchPrefix.replace(NBSP, ' ')
        val normalizedSnippet = cleanSnippet.replace(NBSP, ' ')

        return partTexts.firstOrNull { part ->
            val normalizedPart = part.replace(NBSP, ' ')
            // `contains` subsumes a startsWith test. The second clause catches truncation in either
            // direction: the stored part may extend the notification snippet, or the snippet may
            // extend a part that was itself stored truncated.
            normalizedPart.contains(normalizedPrefix, ignoreCase = true) ||
                normalizedSnippet.startsWith(
                    normalizedPart.take(normalizedSnippet.length.coerceAtMost(normalizedPart.length)),
                    ignoreCase = true,
                )
        }
    }

    /**
     * Reads text content from a telephony MMS part input stream when the database text column is null.
     *
     * @param partId The telephony MMS part row ID.
     * @return The read text body, or `null` if unable to open or read the stream.
     */
    private fun readTextFromPartStream(partId: Long): String? = runCatching {
        val partUri = Uri.withAppendedPath(MMS_PART_URI, partId.toString())
        context.contentResolver.openInputStream(partUri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.readText().takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /**
     * Sanitizes a notification prefix snippet by stripping common notification artifact prefixes
     * and trailing ellipsis markers.
     *
     * @param snippet The raw snippet string to sanitize.
     * @return The cleaned snippet text.
     */
    internal fun sanitizeSnippet(snippet: String): String = snippet
        .removePrefix("Image\r\n")
        .removePrefix("Image\n")
        .removePrefix("Image ")
        .removePrefix("Image")
        .removePrefix("Photo\r\n")
        .removePrefix("Photo\n")
        .removePrefix("Photo ")
        .removePrefix("Photo")
        .removeSuffix("…")
        .removeSuffix("...")
        .trim()

    companion object {
        /** Tag for logcat output. */
        private const val TAG: String = "MmsTextResolver"

        /** Default maximum number of retry attempts when polling for MMS text parts. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 6

        /** Default suspension delay between polling attempts in milliseconds. */
        const val DEFAULT_DELAY_MILLIS: Long = 750L

        /** Maximum number of recent MMS part records to scan. */
        const val MAX_RECORDS_TO_CHECK: Int = 100

        /** Number of characters from the snippet prefix used for robust database text search. */
        const val SEARCH_PREFIX_LENGTH: Int = 40

        /** Non-breaking space, normalized to a plain space on both sides of every comparison. */
        private const val NBSP: Char = '\u00A0'

        /** Telephony MMS part content URI string. */
        const val MMS_PART_URI_STRING: String = "content://mms/part"

        /** Telephony MMS part content URI. */
        val MMS_PART_URI: Uri get() = Uri.parse(MMS_PART_URI_STRING)

        /** Column name for record identifier. */
        const val COLUMN_ID: String = "_id"

        /** Column name for plain text body content. */
        const val COLUMN_TEXT: String = "text"

        /** Column name for part content MIME type. */
        const val COLUMN_CONTENT_TYPE: String = "ct"

        /** MIME type for plain text MMS parts. */
        const val MIME_TYPE_TEXT_PLAIN: String = "text/plain"
    }
}
