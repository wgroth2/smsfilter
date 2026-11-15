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

import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [StopListViewModel].
 *
 * The blank-input rule carries real weight: an empty keyword is a substring of every message, so
 * accepting one would silence the filter entirely. The add and edit tests pin down that a rejected
 * save is reported to the UI rather than silently dropped, and that the edit dialog stays open
 * until a save actually succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopListViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var stopListDao: RecordingStopListDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stopListDao = RecordingStopListDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a ViewModel wired to the recording DAO.
     *
     * @return The ViewModel under test.
     */
    private fun createViewModel(): StopListViewModel = StopListViewModel(stopListDao)

    // ---------------------------------------------------------------------
    // Add
    // ---------------------------------------------------------------------

    /**
     * Tests that a keyword is trimmed before it is persisted.
     *
     * Preconditions: add is called with surrounding whitespace.
     * Expected: the DAO receives the trimmed keyword, so it matches the same way a typed one does.
     */
    @Test
    fun `add trims surrounding whitespace`() = runTest(testDispatcher) {
        createViewModel().add("  promo  ", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertEquals(listOf("promo"), stopListDao.inserted.map { it.keyword })
    }

    /**
     * Tests that the matching options reach the database.
     *
     * Preconditions: add with both options on.
     * Expected: the inserted row carries both flags.
     */
    @Test
    fun `add persists the matching options`() = runTest(testDispatcher) {
        createViewModel().add("Avis", caseSensitive = true, wholeWord = true)
        advanceUntilIdle()

        val inserted = stopListDao.inserted.single()
        assertTrue(inserted.caseSensitive)
        assertTrue(inserted.wholeWord)
    }

    /**
     * Tests that a successful add is reported so the form can reset.
     *
     * Preconditions: a valid add.
     * Expected: completedAdds increments, no error, not saving.
     */
    @Test
    fun `successful add increments completedAdds`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.add("promo", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        val state = viewModel.addState.value
        assertEquals(1, state.completedAdds)
        assertNull(state.error)
        assertFalse(state.isSaving)
    }

    /**
     * Tests that blank input is rejected.
     *
     * Preconditions: add is called with an empty string and with whitespace only.
     * Expected: nothing reaches the DAO. An empty keyword is a substring of every message and would
     * cause every incoming message to be ignored.
     */
    @Test
    fun `add ignores blank keywords`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.add("", caseSensitive = false, wholeWord = false)
        viewModel.add("   ", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertTrue(stopListDao.inserted.isEmpty())
        assertEquals(0, viewModel.addState.value.completedAdds)
    }

    /**
     * Tests that adding text that already exists is reported instead of silently ignored.
     *
     * Preconditions: "Avis" already stored.
     * Expected: no insert, Duplicate error, completedAdds unchanged so the typed text is kept.
     */
    @Test
    fun `adding an existing keyword reports a duplicate`() = runTest(testDispatcher) {
        stopListDao.rows += StopListEntity(id = 1, keyword = "Avis")
        val viewModel = createViewModel()

        viewModel.add("Avis", caseSensitive = false, wholeWord = true)
        advanceUntilIdle()

        assertTrue(stopListDao.inserted.isEmpty())
        assertEquals(StopListError.Duplicate, viewModel.addState.value.error)
        assertEquals(0, viewModel.addState.value.completedAdds)
    }

    /**
     * Tests that keywords differing only in capitalization are separate entries.
     *
     * Preconditions: "avis" already stored.
     * Expected: adding "Avis" succeeds.
     */
    @Test
    fun `keywords differing only by case are separate entries`() = runTest(testDispatcher) {
        stopListDao.rows += StopListEntity(id = 1, keyword = "avis")
        val viewModel = createViewModel()

        viewModel.add("Avis", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertEquals(listOf("Avis"), stopListDao.inserted.map { it.keyword })
        assertNull(viewModel.addState.value.error)
    }

    /**
     * Tests that an insert skipped by the unique index is mapped to a duplicate.
     *
     * Preconditions: the DAO's insert returns -1 (ignored).
     * Expected: Duplicate error.
     */
    @Test
    fun `ignored insert is reported as a duplicate`() = runTest(testDispatcher) {
        stopListDao.insertReturns = -1L
        val viewModel = createViewModel()

        viewModel.add("promo", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertEquals(StopListError.Duplicate, viewModel.addState.value.error)
    }

    /**
     * Tests that a database failure on add is mapped to SaveFailed rather than crashing.
     *
     * Preconditions: the DAO's insert throws.
     * Expected: SaveFailed error and the add is no longer in progress.
     */
    @Test
    fun `database failure on add is reported`() = runTest(testDispatcher) {
        stopListDao.failWrites = true
        val viewModel = createViewModel()

        viewModel.add("promo", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertEquals(StopListError.SaveFailed, viewModel.addState.value.error)
        assertFalse(viewModel.addState.value.isSaving)
    }

    /**
     * Tests that the add error can be cleared once the user edits the input.
     *
     * Preconditions: a duplicate error is showing.
     * Expected: clearAddError removes it.
     */
    @Test
    fun `clearAddError removes the error`() = runTest(testDispatcher) {
        stopListDao.rows += StopListEntity(id = 1, keyword = "promo")
        val viewModel = createViewModel()
        viewModel.add("promo", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        viewModel.clearAddError()

        assertNull(viewModel.addState.value.error)
    }

    // ---------------------------------------------------------------------
    // Edit
    // ---------------------------------------------------------------------

    /**
     * Tests that editing opens and cancel closes the dialog state.
     *
     * Preconditions: a stored row.
     * Expected: startEdit exposes it; cancelEdit clears it without writing.
     */
    @Test
    fun `startEdit and cancelEdit open and close the dialog`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis")
        val viewModel = createViewModel()

        viewModel.startEdit(row)
        assertEquals(row, viewModel.editState.value?.target)

        viewModel.cancelEdit()
        assertNull(viewModel.editState.value)
        assertTrue(stopListDao.updated.isEmpty())
    }

    /**
     * Tests the motivating edit: turning on "whole word" for an existing keyword.
     *
     * Preconditions: "Avis" stored with both options off.
     * Expected: the row is updated with wholeWord on, keeping its id, and the dialog closes.
     */
    @Test
    fun `saving an option change updates the row and closes the dialog`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis")
        stopListDao.rows += row
        val viewModel = createViewModel()
        viewModel.startEdit(row)

        viewModel.saveEdit("Avis", caseSensitive = false, wholeWord = true)
        advanceUntilIdle()

        assertEquals(listOf(row.copy(wholeWord = true)), stopListDao.updated)
        assertNull(viewModel.editState.value)
    }

    /**
     * Tests that renaming to text another row holds keeps the dialog open with an error.
     *
     * Preconditions: rows "Avis" (id 1) and "avis" (id 2); editing id 2 to "Avis".
     * Expected: no update, the dialog stays open showing Duplicate.
     */
    @Test
    fun `renaming to an existing keyword keeps the dialog open with an error`() = runTest(testDispatcher) {
        stopListDao.rows += StopListEntity(id = 1, keyword = "Avis")
        val target = StopListEntity(id = 2, keyword = "avis")
        stopListDao.rows += target
        val viewModel = createViewModel()
        viewModel.startEdit(target)

        viewModel.saveEdit("Avis", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertTrue(stopListDao.updated.isEmpty())
        val state = viewModel.editState.value
        assertNotNull("the dialog must stay open", state)
        assertEquals(StopListError.Duplicate, state?.error)
        assertFalse(state?.isSaving ?: true)
    }

    /**
     * Tests that saving with nothing changed closes the dialog without a write.
     *
     * Preconditions: editing a row and saving its original values.
     * Expected: no update; dialog closed.
     */
    @Test
    fun `saving with no changes closes without writing`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis", wholeWord = true)
        val viewModel = createViewModel()
        viewModel.startEdit(row)

        viewModel.saveEdit("  Avis ", caseSensitive = false, wholeWord = true)
        advanceUntilIdle()

        assertTrue(stopListDao.updated.isEmpty())
        assertNull(viewModel.editState.value)
    }

    /**
     * Tests that a blank edit is ignored and the dialog stays open.
     *
     * Preconditions: editing a row and saving blank text.
     * Expected: no update; dialog still open.
     */
    @Test
    fun `blank edit is ignored`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis")
        val viewModel = createViewModel()
        viewModel.startEdit(row)

        viewModel.saveEdit("   ", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        assertTrue(stopListDao.updated.isEmpty())
        assertEquals(row, viewModel.editState.value?.target)
    }

    /**
     * Tests that a database failure on edit keeps the dialog open with SaveFailed.
     *
     * Preconditions: the DAO's update throws.
     * Expected: the dialog stays open showing SaveFailed.
     */
    @Test
    fun `database failure on edit keeps the dialog open`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis")
        stopListDao.rows += row
        stopListDao.failWrites = true
        val viewModel = createViewModel()
        viewModel.startEdit(row)

        viewModel.saveEdit("Avis", caseSensitive = true, wholeWord = false)
        advanceUntilIdle()

        assertEquals(StopListError.SaveFailed, viewModel.editState.value?.error)
    }

    /**
     * Tests that editing a row that was deleted meanwhile is reported rather than claimed as saved.
     *
     * Preconditions: the DAO's update reports zero rows changed.
     * Expected: SaveFailed; the dialog stays open.
     */
    @Test
    fun `editing a row that no longer exists is reported`() = runTest(testDispatcher) {
        val row = StopListEntity(id = 3, keyword = "Avis")
        stopListDao.updateReturns = 0
        val viewModel = createViewModel()
        viewModel.startEdit(row)

        viewModel.saveEdit("Avis", caseSensitive = true, wholeWord = false)
        advanceUntilIdle()

        assertEquals(StopListError.SaveFailed, viewModel.editState.value?.error)
    }

    /**
     * Tests that the edit error clears once the user changes a field.
     *
     * Preconditions: a duplicate error showing in the dialog.
     * Expected: clearEditError removes it but leaves the dialog open.
     */
    @Test
    fun `clearEditError removes the error and keeps the dialog`() = runTest(testDispatcher) {
        stopListDao.rows += StopListEntity(id = 1, keyword = "Avis")
        val target = StopListEntity(id = 2, keyword = "avis")
        val viewModel = createViewModel()
        viewModel.startEdit(target)
        viewModel.saveEdit("Avis", caseSensitive = false, wholeWord = false)
        advanceUntilIdle()

        viewModel.clearEditError()

        assertNull(viewModel.editState.value?.error)
        assertEquals(target, viewModel.editState.value?.target)
    }

    // ---------------------------------------------------------------------
    // Delete
    // ---------------------------------------------------------------------

    /**
     * Tests that delete forwards the entity to the DAO.
     *
     * Preconditions: an existing stop-list row.
     * Expected: the DAO is asked to delete that exact row.
     */
    @Test
    fun `delete forwards the entity to the DAO`() = runTest(testDispatcher) {
        val entity = StopListEntity(id = 7, keyword = "fundraiser")

        createViewModel().delete(entity)
        advanceUntilIdle()

        assertEquals(listOf(entity), stopListDao.deleted)
    }

    /**
     * Tests that a failed delete is contained rather than crashing the app.
     *
     * Preconditions: the DAO's delete throws.
     * Expected: no exception escapes.
     */
    @Test
    fun `failed delete does not crash`() = runTest(testDispatcher) {
        stopListDao.failWrites = true

        createViewModel().delete(StopListEntity(id = 7, keyword = "fundraiser"))
        advanceUntilIdle()

        assertTrue(stopListDao.deleted.isEmpty())
    }

    /** A [StopListDao] backed by an in-memory row list, recording every write. */
    private class RecordingStopListDao : StopListDao {
        /** Stored rows, consulted by [findByKeyword]. */
        val rows: MutableList<StopListEntity> = mutableListOf()

        /** Rows passed to [insert], in call order. */
        val inserted: MutableList<StopListEntity> = mutableListOf()

        /** Rows passed to [update], in call order. */
        val updated: MutableList<StopListEntity> = mutableListOf()

        /** Rows passed to [delete], in call order. */
        val deleted: MutableList<StopListEntity> = mutableListOf()

        /** Backing list for [observeAll]. */
        val keywordsFlow: MutableStateFlow<List<StopListEntity>> = MutableStateFlow(emptyList())

        /** Value [insert] returns; `-1` simulates an insert ignored by the unique index. */
        var insertReturns: Long = 1L

        /** Value [update] returns; `0` simulates a row that no longer exists. */
        var updateReturns: Int = 1

        /** When `true`, every write throws to simulate a database failure. */
        var failWrites: Boolean = false

        override fun observeAll(): Flow<List<StopListEntity>> = keywordsFlow
        override suspend fun getAll(): List<StopListEntity> = keywordsFlow.value

        override suspend fun insert(entity: StopListEntity): Long {
            if (failWrites) throw IllegalStateException("simulated database failure")
            if (insertReturns != -1L) inserted += entity
            return insertReturns
        }

        override suspend fun update(entity: StopListEntity): Int {
            if (failWrites) throw IllegalStateException("simulated database failure")
            if (updateReturns > 0) updated += entity
            return updateReturns
        }

        override suspend fun findByKeyword(keyword: String): StopListEntity? =
            rows.firstOrNull { it.keyword == keyword }

        override suspend fun delete(entity: StopListEntity) {
            if (failWrites) throw IllegalStateException("simulated database failure")
            deleted += entity
        }

        override suspend fun deleteByKeyword(keyword: String): Int = 0

        override suspend fun count(): Int = keywordsFlow.value.size
    }
}
