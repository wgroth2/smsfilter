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

import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [OptOutPatternsViewModel].
 *
 * Input sanitization and the reset-to-defaults contract. The pattern-trimming cases were carried
 * over unchanged from `SettingsViewModelTest` when this editor became its own destination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptOutPatternsViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var optOutPatternDao: RecordingOptOutPatternDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        optOutPatternDao = RecordingOptOutPatternDao()
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
    private fun createViewModel(): OptOutPatternsViewModel = OptOutPatternsViewModel(optOutPatternDao)

    /**
     * Tests that updatePattern trims leading and trailing whitespace from pattern text before persisting to the DAO.
     *
     * Preconditions: Calling updatePattern with id=42, pattern="  stop2stop  ", STOP reply type, ANYWHERE match mode.
     * Expected: OptOutPatternDao receives an updated entity with trimmed pattern "stop2stop".
     */
    @Test
    fun `updatePattern trims pattern text and updates DAO`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.update(
            id = 42L,
            pattern = "  stop2stop  ",
            replyType = ReplyType.STOP,
            matchMode = MatchMode.ANYWHERE,
        )
        advanceUntilIdle()

        assertEquals(1, optOutPatternDao.updatedPatterns.size)
        val updated = optOutPatternDao.updatedPatterns.single()
        assertEquals(42L, updated.id)
        assertEquals("stop2stop", updated.pattern)
        assertEquals(ReplyType.STOP, updated.replyType)
        assertEquals(MatchMode.ANYWHERE, updated.matchMode)
    }

    /**
     * Tests that updatePattern ignores blank or whitespace-only pattern strings without calling the DAO.
     *
     * Preconditions: Calling updatePattern with whitespace pattern "   ".
     * Expected: No updates are dispatched to [OptOutPatternDao].
     */
    @Test
    fun `updatePattern ignores blank pattern`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.update(
            id = 42L,
            pattern = "   ",
            replyType = ReplyType.END,
            matchMode = MatchMode.LAST_LINE_EXACT,
        )
        advanceUntilIdle()

        assertTrue(optOutPatternDao.updatedPatterns.isEmpty())
    }

    /**
     * Tests that addPattern trims whitespace from pattern text and inserts the new entity into the DAO.
     *
     * Preconditions: Calling addPattern with pattern="  unsubscribe  ", STOP reply type, ANYWHERE match mode.
     * Expected: [OptOutPatternDao] receives an insert with trimmed pattern "unsubscribe".
     */
    @Test
    fun `addPattern trims pattern text and inserts into DAO`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.add(
            pattern = "  unsubscribe  ",
            replyType = ReplyType.STOP,
            matchMode = MatchMode.ANYWHERE,
        )
        advanceUntilIdle()

        assertEquals(1, optOutPatternDao.insertedPatterns.size)
        val inserted = optOutPatternDao.insertedPatterns.single()
        assertEquals("unsubscribe", inserted.pattern)
        assertEquals(ReplyType.STOP, inserted.replyType)
        assertEquals(MatchMode.ANYWHERE, inserted.matchMode)
    }

    /**
     * Tests that addPattern ignores blank or whitespace-only pattern strings without inserting into the DAO.
     *
     * Preconditions: Calling addPattern with whitespace pattern "   ".
     * Expected: No inserts are dispatched to [OptOutPatternDao].
     */
    @Test
    fun `addPattern ignores blank pattern`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.add(
            pattern = "   ",
            replyType = ReplyType.STOP,
            matchMode = MatchMode.ANYWHERE,
        )
        advanceUntilIdle()

        assertTrue(optOutPatternDao.insertedPatterns.isEmpty())
    }

    /**
     * Tests that resetToDefaults clears the table before restoring the shipped patterns.
     *
     * Preconditions: a table holding one user-authored pattern.
     * Expected: deleteAll runs first, then every declared default is re-inserted through the DAO —
     * proving the reset goes through the converters rather than the seeding SQL.
     */
    @Test
    fun `resetToDefaults clears then reinserts declared defaults`() = runTest(testDispatcher) {
        optOutPatternDao.patternsFlow.value = listOf(
            OptOutPatternEntity(id = 1, pattern = "custom", replyType = ReplyType.STOP, matchMode = MatchMode.ANYWHERE),
        )

        createViewModel().resetToDefaults()
        advanceUntilIdle()

        assertTrue("deleteAll must run before the re-insert", optOutPatternDao.deleteAllCalled)
        assertEquals(
            com.digiroth.smsfilter.data.db.AppDatabase.DEFAULT_PATTERNS.map { it.pattern },
            optOutPatternDao.bulkInserted.map { it.pattern },
        )
    }

    /** A [OptOutPatternDao] recording the calls made against it. */
    private class RecordingOptOutPatternDao : OptOutPatternDao {
        /** Patterns passed to [insert], in call order. */
        val insertedPatterns: MutableList<OptOutPatternEntity> = mutableListOf()

        /** Patterns passed to [update], in call order. */
        val updatedPatterns: MutableList<OptOutPatternEntity> = mutableListOf()

        /** Patterns passed to [insertAll], flattened in call order. */
        val bulkInserted: MutableList<OptOutPatternEntity> = mutableListOf()

        /** Whether [deleteAll] was invoked. */
        var deleteAllCalled: Boolean = false

        /** Backing list for [observeAll]. */
        val patternsFlow: MutableStateFlow<List<OptOutPatternEntity>> = MutableStateFlow(emptyList())

        override fun observeAll(): Flow<List<OptOutPatternEntity>> = patternsFlow
        override suspend fun getAll(): List<OptOutPatternEntity> = patternsFlow.value

        override suspend fun insert(entity: OptOutPatternEntity): Long {
            insertedPatterns += entity
            return 1L
        }

        override suspend fun insertAll(entities: List<OptOutPatternEntity>): List<Long> {
            bulkInserted += entities
            return entities.map { 1L }
        }

        override suspend fun update(pattern: OptOutPatternEntity): Int {
            updatedPatterns += pattern
            return 1
        }

        override suspend fun delete(entity: OptOutPatternEntity) = Unit

        override suspend fun count(): Int = patternsFlow.value.size

        override suspend fun deleteAll(): Int {
            deleteAllCalled = true
            val removed = patternsFlow.value.size
            patternsFlow.value = emptyList()
            return removed
        }
    }
}
