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

package com.digiroth.smsfilter.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.AppDatabase
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the Opt-Out Patterns tab.
 *
 * For official Android documentation on architecture and ViewModel StateFlow management, see:
 * - Architecture Guide: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 *
 * The pattern list is exposed straight from Room rather than mirrored into a UI state class, so an
 * edit shows up without this class having to track the database.
 *
 * @property optOutPatternDao Data access for opt-out patterns.
 */
@HiltViewModel
class OptOutPatternsViewModel @Inject constructor(
    private val optOutPatternDao: OptOutPatternDao,
) : ViewModel() {

    /** Live opt-out patterns, observed from Room. */
    val patterns: StateFlow<List<OptOutPatternEntity>> = optOutPatternDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Adds an opt-out pattern.
     *
     * @param pattern The pattern text; blank input is ignored.
     * @param replyType Which keyword to reply with.
     * @param matchMode How the pattern is evaluated.
     */
    fun add(pattern: String, replyType: ReplyType, matchMode: MatchMode) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            optOutPatternDao.insert(
                OptOutPatternEntity(pattern = trimmed, replyType = replyType, matchMode = matchMode),
            )
        }
    }

    /**
     * Updates an existing opt-out pattern.
     *
     * Blank input is ignored.
     *
     * @param id The row ID of the pattern to update.
     * @param pattern The updated pattern text; blank input is ignored.
     * @param replyType Which keyword to reply with.
     * @param matchMode How the pattern is evaluated.
     */
    fun update(id: Long, pattern: String, replyType: ReplyType, matchMode: MatchMode) {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            optOutPatternDao.update(
                OptOutPatternEntity(
                    id = id,
                    pattern = trimmed,
                    replyType = replyType,
                    matchMode = matchMode,
                ),
            )
        }
    }

    /** @param entity The pattern row to remove. */
    fun delete(entity: OptOutPatternEntity) {
        viewModelScope.launch { optOutPatternDao.delete(entity) }
    }

    /**
     * Discards every stored pattern and re-inserts the declared defaults.
     *
     * Re-inserts [AppDatabase.DEFAULT_PATTERNS] through the DAO rather than re-running the seeding
     * SQL in `AppDatabase`: that SQL writes enum names as string literals, and `RoomConverters`
     * documents how the two drift apart silently. Going through the entity list keeps the
     * converters as the single encoder of those names.
     *
     * Destructive — it discards user-authored patterns too, so the UI must confirm first.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            optOutPatternDao.deleteAll()
            optOutPatternDao.insertAll(AppDatabase.DEFAULT_PATTERNS)
        }
    }

    private companion object {
        /** Keeps the Room flow alive briefly across configuration changes. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
