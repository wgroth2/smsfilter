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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [StopListViewModel].
 *
 * The blank-input rule carries real weight: an empty keyword is a substring of every message, so
 * accepting one would silence the filter entirely.
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

    /**
     * Tests that a keyword is trimmed before it is persisted.
     *
     * Preconditions: add is called with surrounding whitespace.
     * Expected: the DAO receives the trimmed keyword, so it matches the same way a typed one does.
     */
    @Test
    fun `add trims surrounding whitespace`() = runTest(testDispatcher) {
        createViewModel().add("  promo  ")
        advanceUntilIdle()

        assertEquals(listOf("promo"), stopListDao.inserted.map { it.keyword })
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
        viewModel.add("")
        viewModel.add("   ")
        advanceUntilIdle()

        assertTrue(stopListDao.inserted.isEmpty())
    }

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

    /** A [StopListDao] recording the calls made against it. */
    private class RecordingStopListDao : StopListDao {
        /** Rows passed to [insert], in call order. */
        val inserted: MutableList<StopListEntity> = mutableListOf()

        /** Rows passed to [delete], in call order. */
        val deleted: MutableList<StopListEntity> = mutableListOf()

        /** Backing list for [observeAll]. */
        val keywordsFlow: MutableStateFlow<List<StopListEntity>> = MutableStateFlow(emptyList())

        override fun observeAll(): Flow<List<StopListEntity>> = keywordsFlow
        override suspend fun getAll(): List<StopListEntity> = keywordsFlow.value

        override suspend fun insert(entity: StopListEntity): Long {
            inserted += entity
            return 1L
        }

        override suspend fun delete(entity: StopListEntity) {
            deleted += entity
        }

        override suspend fun deleteByKeyword(keyword: String): Int = 0

        override suspend fun count(): Int = keywordsFlow.value.size
    }
}
