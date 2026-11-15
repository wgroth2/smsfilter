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

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Why a stop-list save was rejected, mapped from DAO outcomes and exceptions so the UI can show a
 * specific message instead of a raw database error.
 */
sealed interface StopListError {

    /** Another entry already has exactly this text (same spelling and capitalization). */
    data object Duplicate : StopListError

    /** The database write failed for any other reason. */
    data object SaveFailed : StopListError
}

/** The outcome of one attempt to persist a stop-list entry. */
sealed interface StopListSaveResult {

    /** The entry was written. */
    data object Saved : StopListSaveResult

    /**
     * The entry was not written.
     *
     * @property error Why the save was rejected.
     */
    data class Rejected(val error: StopListError) : StopListSaveResult
}

/**
 * State of the "add keyword" form.
 *
 * The typed text and checkbox values live in the composable, so typing stays responsive and
 * survives process death. This class carries only what the ViewModel decides.
 *
 * @property error Why the most recent add failed, or `null` if it did not fail. Cleared as soon as
 *   the user edits the input again.
 * @property isSaving `true` while an add is being written, so the Add button can be disabled
 *   against double taps.
 * @property completedAdds How many adds have succeeded on this ViewModel. The composable resets its
 *   input when this changes; a counter rather than a one-shot event is used so the signal survives
 *   recomposition and configuration changes without being consumed twice.
 */
data class StopListAddState(
    val error: StopListError? = null,
    val isSaving: Boolean = false,
    val completedAdds: Int = 0,
)

/**
 * State of the open "edit keyword" dialog.
 *
 * Held in the ViewModel rather than the composable so the dialog survives rotation, and so it stays
 * open until the save is confirmed rather than closing on the tap.
 *
 * @property target The stored entry being edited, as it was when editing began.
 * @property error Why the most recent save failed, or `null` if it did not fail.
 * @property isSaving `true` while the edit is being written.
 */
data class StopListEditState(
    val target: StopListEntity,
    val error: StopListError? = null,
    val isSaving: Boolean = false,
)

/**
 * State holder for the Stop List tab.
 *
 * For official Android documentation on architecture and ViewModel StateFlow management, see:
 * - Architecture Guide: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 *
 * The list is exposed straight from Room rather than mirrored into a UI state class, so an edit
 * shows up without this class having to track the database. Add and edit outcomes are reported
 * through [addState] and [editState] so that a rejected save is shown to the user instead of
 * being silently dropped.
 *
 * @property stopListDao Data access for stop-list keywords.
 */
@HiltViewModel
class StopListViewModel @Inject constructor(
    private val stopListDao: StopListDao,
) : ViewModel() {

    /** Live stop list, observed from Room. */
    val keywords: StateFlow<List<StopListEntity>> = stopListDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _addState: MutableStateFlow<StopListAddState> = MutableStateFlow(StopListAddState())

    /** Outcome of the add form; see [StopListAddState]. */
    val addState: StateFlow<StopListAddState> = _addState.asStateFlow()

    private val _editState: MutableStateFlow<StopListEditState?> = MutableStateFlow(null)

    /** The open edit dialog's state, or `null` when no entry is being edited. */
    val editState: StateFlow<StopListEditState?> = _editState.asStateFlow()

    /**
     * Adds a stop-list keyword with its matching options.
     *
     * Blank input is ignored: an empty keyword is a substring of every message and would silence
     * the app entirely. A keyword whose exact text already exists is rejected with
     * [StopListError.Duplicate] rather than silently ignored.
     *
     * @param keyword The keyword to add; surrounding whitespace is trimmed.
     * @param caseSensitive Whether the keyword must match with exact capitalization.
     * @param wholeWord Whether the keyword must match as a whole word.
     */
    fun add(keyword: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty() || _addState.value.isSaving) return

        _addState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val entity = StopListEntity(keyword = trimmed, caseSensitive = caseSensitive, wholeWord = wholeWord)
            when (val result = save(entity)) {
                StopListSaveResult.Saved -> _addState.update {
                    it.copy(isSaving = false, error = null, completedAdds = it.completedAdds + 1)
                }
                is StopListSaveResult.Rejected -> _addState.update {
                    it.copy(isSaving = false, error = result.error)
                }
            }
        }
    }

    /** Clears the add form's error, called when the user changes the input after a failure. */
    fun clearAddError() {
        _addState.update { it.copy(error = null) }
    }

    /**
     * Opens the edit dialog for an entry.
     *
     * @param entity The stored entry to edit.
     */
    fun startEdit(entity: StopListEntity) {
        _editState.value = StopListEditState(target = entity)
    }

    /** Closes the edit dialog without saving. */
    fun cancelEdit() {
        _editState.value = null
    }

    /** Clears the edit dialog's error, called when the user changes a field after a failure. */
    fun clearEditError() {
        _editState.update { it?.copy(error = null) }
    }

    /**
     * Saves the open edit dialog.
     *
     * The dialog closes only once the write succeeds. On a rejected save it stays open with
     * [StopListEditState.error] set, so the user's changes are not lost. Saving with nothing
     * changed simply closes the dialog.
     *
     * @param keyword The edited keyword; surrounding whitespace is trimmed. Blank input is ignored.
     * @param caseSensitive Whether the keyword must match with exact capitalization.
     * @param wholeWord Whether the keyword must match as a whole word.
     */
    fun saveEdit(keyword: String, caseSensitive: Boolean, wholeWord: Boolean) {
        val current = _editState.value ?: return
        if (current.isSaving) return
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return

        val updated = current.target.copy(keyword = trimmed, caseSensitive = caseSensitive, wholeWord = wholeWord)
        if (updated == current.target) {
            _editState.value = null
            return
        }

        _editState.value = current.copy(isSaving = true, error = null)
        viewModelScope.launch {
            when (val result = save(updated)) {
                StopListSaveResult.Saved -> _editState.value = null
                is StopListSaveResult.Rejected -> _editState.update {
                    it?.copy(isSaving = false, error = result.error)
                }
            }
        }
    }

    /**
     * Deletes a stop-list entry.
     *
     * @param entity The stop-list row to remove.
     */
    fun delete(entity: StopListEntity) {
        viewModelScope.launch {
            runCatching { stopListDao.delete(entity) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Failed to delete stop-list keyword", error)
                }
        }
    }

    /**
     * Inserts a new entry (id `0`) or updates an existing one, mapping every failure to a
     * [StopListSaveResult.Rejected].
     *
     * Duplicates are checked up front with an exact-text lookup, which mirrors the unique index.
     * The insert's ignored-row result and the update's constraint exception are still mapped to
     * [StopListError.Duplicate], covering the narrow race where another write lands between the
     * check and the save.
     *
     * @param entity The entry to persist.
     * @return [StopListSaveResult.Saved], or [StopListSaveResult.Rejected] with the reason.
     */
    private suspend fun save(entity: StopListEntity): StopListSaveResult = try {
        val existing = stopListDao.findByKeyword(entity.keyword)
        when {
            (existing != null) && (existing.id != entity.id) -> {
                Log.d(TAG, "Rejected stop-list save: keyword already exists")
                StopListSaveResult.Rejected(StopListError.Duplicate)
            }
            entity.id == NEW_ROW_ID -> {
                if (stopListDao.insert(entity) == INSERT_IGNORED) {
                    StopListSaveResult.Rejected(StopListError.Duplicate)
                } else {
                    Log.d(TAG, "Stop-list keyword added")
                    StopListSaveResult.Saved
                }
            }
            else -> {
                if (stopListDao.update(entity) == 0) {
                    Log.w(TAG, "Stop-list keyword to update no longer exists")
                    StopListSaveResult.Rejected(StopListError.SaveFailed)
                } else {
                    Log.d(TAG, "Stop-list keyword updated")
                    StopListSaveResult.Saved
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SQLiteConstraintException) {
        Log.w(TAG, "Stop-list save hit the unique index", error)
        StopListSaveResult.Rejected(StopListError.Duplicate)
    } catch (error: Exception) {
        Log.e(TAG, "Stop-list save failed", error)
        StopListSaveResult.Rejected(StopListError.SaveFailed)
    }

    private companion object {
        /** Logging tag for this class. */
        const val TAG: String = "StopListViewModel"

        /** Keeps the Room flow alive briefly across configuration changes, in milliseconds. */
        const val STOP_TIMEOUT_MS: Long = 5_000L

        /** The `id` of an entity that has not been inserted yet; Room assigns the real id. */
        const val NEW_ROW_ID: Long = 0L

        /** Room's return value from an `IGNORE` insert that was skipped by a constraint. */
        const val INSERT_IGNORED: Long = -1L
    }
}
