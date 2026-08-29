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
 * Notification payloads from messaging apps (e.g. Google Messages) often truncate MMS body text
 * or prepend image/attachment placeholders such as "Image\n". This resolver queries the telephony
 * MMS part table (`content://mms/part`) to locate the complete plain text part associated with recent
 * incoming MMS messages.
 *
 * @property context The application context used to obtain the content resolver.
 */
@Singleton
open class MmsTextResolver @Inject constructor(
    @ApplicationContext private val context: Context,
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
     * @param maxAttempts Maximum number of query attempts before giving up. Defaults to 3.
     * @param delayMillis Suspension duration between retry attempts in milliseconds. Defaults to 350ms.
     * @return The resolved full MMS body text, or `null` if resolution failed across all attempts.
     */
    suspend fun resolveFullMmsTextWithRetry(
        prefixSnippet: String? = null,
        maxAttempts: Int = 3,
        delayMillis: Long = 350L,
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
     * checking up to the newest 100 records. If [prefixSnippet] is provided, attempts to find a record
     * whose text starts with or contains the sanitized search prefix (first 40 characters, stripping
     * leading attachment labels such as "Image\n" and trailing ellipsis). If [prefixSnippet] is null
     * or blank, returns the most recent plain text part.
     *
     * @param prefixSnippet The initial snippet or truncated notification body to match against, or `null`.
     * @return The full MMS message text body if resolved, or `null` if not found or on error.
     */
    open fun resolveFullMmsText(prefixSnippet: String? = null): String? = runCatching {
        val cleanSnippet = prefixSnippet?.let(::sanitizeSnippet)?.takeIf { it.isNotBlank() }
        val searchPrefix = cleanSnippet?.take(SEARCH_PREFIX_LENGTH)?.trim()?.takeIf { it.isNotBlank() }

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
            val textColumnIndex = cursor.getColumnIndex(COLUMN_TEXT)
            if (textColumnIndex == -1) {
                Log.w(TAG, "MMS part cursor missing '$COLUMN_TEXT' column")
                return@runCatching null
            }

            var recordsChecked = 0
            var fallbackFirstText: String? = null

            while (cursor.moveToNext() && (recordsChecked < MAX_RECORDS_TO_CHECK)) {
                recordsChecked++
                val text = cursor.getString(textColumnIndex)
                if (text.isNullOrBlank()) continue

                if (fallbackFirstText == null) {
                    fallbackFirstText = text
                }

                if (searchPrefix != null) {
                    if (text.startsWith(searchPrefix, ignoreCase = true) ||
                        text.contains(searchPrefix, ignoreCase = true) ||
                        cleanSnippet.startsWith(text.take(cleanSnippet.length.coerceAtMost(text.length)), ignoreCase = true)
                    ) {
                        Log.d(TAG, "Matched full MMS text ($recordsChecked records examined)")
                        return@runCatching text
                    }
                }
            }

            // If no specific prefix was requested and we found a recent text part, return it
            if ((cleanSnippet == null) && (fallbackFirstText != null)) {
                Log.d(TAG, "Retrieved latest MMS text from provider")
                return@runCatching fallbackFirstText
            }

            null
        }
    }.onFailure { error ->
        Log.e(TAG, "Failed to resolve full MMS text from telephony provider", error)
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

        /** Maximum number of recent MMS part records to scan. */
        const val MAX_RECORDS_TO_CHECK: Int = 100

        /** Number of characters from the snippet prefix used for robust database text search. */
        const val SEARCH_PREFIX_LENGTH: Int = 40

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
