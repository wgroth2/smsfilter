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

import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * JVM unit tests for [DetectionLogViewModel], verifying filter state and DAO emission queries.
 *
 * Verifies that switching UI log filters queries the appropriate DAO flow (all events vs. specific
 * event types) and that clearing logs delegates to the DAO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetectionLogViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: RecordingDetectionLogDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = RecordingDetectionLogDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Tests that a newly created ViewModel defaults to the ALL filter.
     *
     * Preconditions: Newly initialized [DetectionLogViewModel].
     * Expected: [DetectionLogViewModel.filter] StateFlow emits [LogFilter.ALL].
     */
    @Test
    fun defaultFilter_isAll() {
        val viewModel = DetectionLogViewModel(fakeDao)
        assertEquals(LogFilter.ALL, viewModel.filter.value)
    }

    /**
     * Tests that setFilter updates the ViewModel's filter StateFlow for all filter options.
     *
     * Preconditions: Calling setFilter with DETECTIONS, IGNORED, NO_MATCH, and ALL sequentially.
     * Expected: Filter StateFlow value matches the newly applied filter at each step.
     */
    @Test
    fun setFilter_updatesFilterState() {
        val viewModel = DetectionLogViewModel(fakeDao)

        viewModel.setFilter(LogFilter.DETECTIONS)
        assertEquals(LogFilter.DETECTIONS, viewModel.filter.value)

        viewModel.setFilter(LogFilter.IGNORED)
        assertEquals(LogFilter.IGNORED, viewModel.filter.value)

        viewModel.setFilter(LogFilter.NO_MATCH)
        assertEquals(LogFilter.NO_MATCH, viewModel.filter.value)

        viewModel.setFilter(LogFilter.ALL)
        assertEquals(LogFilter.ALL, viewModel.filter.value)
    }

    /**
     * Tests that the ALL filter collects entries from observeAll on the DAO.
     *
     * Preconditions: DAO populated with 3 entries across DETECTION, IGNORED, and NO_MATCH.
     * Expected: ViewModel entries StateFlow receives all 3 items and observeAll is called on the DAO.
     */
    @Test
    fun filterAll_observesAllDaoEntries() = runTest(testDispatcher) {
        val detection = DetectionLogEntity(id = 1, timestamp = 100L, eventType = LogEventType.DETECTION, messagePreview = "msg1")
        val ignored = DetectionLogEntity(id = 2, timestamp = 200L, eventType = LogEventType.IGNORED, messagePreview = "msg2")
        val noMatch = DetectionLogEntity(id = 3, timestamp = 300L, eventType = LogEventType.NO_MATCH, messagePreview = "msg3")
        fakeDao.allFlow.value = listOf(noMatch, ignored, detection)

        val viewModel = DetectionLogViewModel(fakeDao)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.entries.collect {}
        }

        advanceUntilIdle()
        assertEquals(3, viewModel.entries.value.size)
        assertTrue(fakeDao.observeAllCalled)
        collectJob.cancel()
    }

    /**
     * Tests that selecting the DETECTIONS filter queries the DAO specifically for DETECTION events.
     *
     * Preconditions: Filter set to [LogFilter.DETECTIONS] and DAO byTypeFlow populated with a detection event.
     * Expected: DAO queried for [LogEventType.DETECTION] and ViewModel contains only the detection entry.
     */
    @Test
    fun filterDetections_observesDetectionsOnly() = runTest(testDispatcher) {
        val detection = DetectionLogEntity(id = 1, timestamp = 100L, eventType = LogEventType.DETECTION, messagePreview = "msg1")
        fakeDao.byTypeFlow.value = listOf(detection)

        val viewModel = DetectionLogViewModel(fakeDao)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.entries.collect {}
        }

        viewModel.setFilter(LogFilter.DETECTIONS)
        advanceUntilIdle()

        assertEquals(LogEventType.DETECTION, fakeDao.lastObservedType)
        assertEquals(1, viewModel.entries.value.size)
        assertEquals(LogEventType.DETECTION, viewModel.entries.value.single().eventType)
        collectJob.cancel()
    }

    /**
     * Tests that selecting the IGNORED filter queries the DAO specifically for IGNORED events.
     *
     * Preconditions: Filter set to [LogFilter.IGNORED] and DAO byTypeFlow populated with an ignored event.
     * Expected: DAO queried for [LogEventType.IGNORED] and ViewModel contains only the ignored entry.
     */
    @Test
    fun filterIgnored_observesIgnoredOnly() = runTest(testDispatcher) {
        val ignored = DetectionLogEntity(id = 2, timestamp = 200L, eventType = LogEventType.IGNORED, messagePreview = "msg2")
        fakeDao.byTypeFlow.value = listOf(ignored)

        val viewModel = DetectionLogViewModel(fakeDao)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.entries.collect {}
        }

        viewModel.setFilter(LogFilter.IGNORED)
        advanceUntilIdle()

        assertEquals(LogEventType.IGNORED, fakeDao.lastObservedType)
        assertEquals(1, viewModel.entries.value.size)
        assertEquals(LogEventType.IGNORED, viewModel.entries.value.single().eventType)
        collectJob.cancel()
    }

    /**
     * Tests that selecting the NO_MATCH filter queries the DAO specifically for NO_MATCH events.
     *
     * Preconditions: Filter set to [LogFilter.NO_MATCH] and DAO byTypeFlow populated with a no-match event.
     * Expected: DAO queried for [LogEventType.NO_MATCH] and ViewModel contains only the no-match entry.
     */
    @Test
    fun filterNoMatch_observesNoMatchOnly() = runTest(testDispatcher) {
        val noMatch = DetectionLogEntity(id = 3, timestamp = 300L, eventType = LogEventType.NO_MATCH, messagePreview = "msg3")
        fakeDao.byTypeFlow.value = listOf(noMatch)

        val viewModel = DetectionLogViewModel(fakeDao)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.entries.collect {}
        }

        viewModel.setFilter(LogFilter.NO_MATCH)
        advanceUntilIdle()

        assertEquals(LogEventType.NO_MATCH, fakeDao.lastObservedType)
        assertEquals(1, viewModel.entries.value.size)
        assertEquals(LogEventType.NO_MATCH, viewModel.entries.value.single().eventType)
        collectJob.cancel()
    }

    /**
     * Tests that clearLog delegates deletion to the DAO clear method.
     *
     * Preconditions: [DetectionLogViewModel.clearLog] called.
     * Expected: DAO clear method is invoked.
     */
    @Test
    fun clearLog_callsDaoClear() = runTest(testDispatcher) {
        val viewModel = DetectionLogViewModel(fakeDao)

        viewModel.clearLog()
        advanceUntilIdle()

        assertTrue(fakeDao.clearCalled)
    }

    /**
     * Test double recording DAO invocations and driving flow emissions.
     */
    private class RecordingDetectionLogDao : DetectionLogDao {
        var observeAllCalled: Boolean = false
        var lastObservedType: LogEventType? = null
        var clearCalled: Boolean = false

        val allFlow: MutableStateFlow<List<DetectionLogEntity>> = MutableStateFlow(emptyList())
        val byTypeFlow: MutableStateFlow<List<DetectionLogEntity>> = MutableStateFlow(emptyList())

        override fun observeAll(limit: Int): Flow<List<DetectionLogEntity>> {
            observeAllCalled = true
            return allFlow
        }

        override fun observeRecentActionable(
            limit: Int,
            excludedType: LogEventType,
        ): Flow<List<DetectionLogEntity>> = flowOf(emptyList())

        override fun observeRecentByType(
            eventType: LogEventType,
            limit: Int,
        ): Flow<List<DetectionLogEntity>> {
            lastObservedType = eventType
            return byTypeFlow
        }

        override suspend fun getRecent(limit: Int): List<DetectionLogEntity> = emptyList()

        override suspend fun insert(entity: DetectionLogEntity): Long = 1L

        override suspend fun clear(): Int {
            clearCalled = true
            return 1
        }

        override suspend fun count(): Int = 0
    }
}
