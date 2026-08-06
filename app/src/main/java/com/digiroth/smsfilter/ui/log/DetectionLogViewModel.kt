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

package com.digiroth.smsfilter.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which entries the log screen is showing. */
enum class LogFilter {
    /**
     * Every message the app acted on: detections and ignored messages together.
     *
     * Messages that matched nothing are deliberately left out. They are high-volume diagnostic
     * data — one row per non-matching text the device receives — and would crowd out the rows the
     * user opened the log to read. They are reachable under [NO_MATCH] instead.
     */
    ALL,

    /** Only opt-out detections. */
    DETECTIONS,

    /** Only messages that were ignored. */
    IGNORED,

    /**
     * Only messages that were examined and matched nothing.
     *
     * This is the chip that answers "is the app receiving my texts at all?", which is why these
     * rows are logged even though they trigger no action.
     */
    NO_MATCH,
}

/**
 * State holder for the activity and detection log.
 *
 * Filtering is done in SQL rather than in memory: the query already caps results at
 * [DetectionLogEntity.MAX_DISPLAYED_ENTRIES], and filtering a capped list client-side would show
 * fewer than that many rows of the selected kind whenever the newest hundred entries were mostly
 * of the other kind.
 *
 * [LogFilter.ALL] means "every message the app acted on", not "every row in the table":
 * [LogEventType.NO_MATCH] rows are excluded there and surfaced only under [LogFilter.NO_MATCH],
 * because one is written for every non-matching text received and they would otherwise fill the
 * capped window on their own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetectionLogViewModel @Inject constructor(
    private val detectionLogDao: DetectionLogDao,
) : ViewModel() {

    private val _filter = MutableStateFlow(LogFilter.ALL)

    /** The active filter chip. */
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    /** Log entries matching the active filter, newest first. */
    val entries: StateFlow<List<DetectionLogEntity>> = _filter
        .flatMapLatest { selected ->
            when (selected) {
                LogFilter.ALL -> detectionLogDao.observeRecentActionable()
                LogFilter.DETECTIONS -> detectionLogDao.observeRecentByType(LogEventType.DETECTION)
                LogFilter.IGNORED -> detectionLogDao.observeRecentByType(LogEventType.IGNORED)
                LogFilter.NO_MATCH -> detectionLogDao.observeRecentByType(LogEventType.NO_MATCH)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** @param selected The filter chip the user tapped. */
    fun setFilter(selected: LogFilter) {
        _filter.value = selected
    }

    /** Deletes every log entry. */
    fun clearLog() {
        viewModelScope.launch { detectionLogDao.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
