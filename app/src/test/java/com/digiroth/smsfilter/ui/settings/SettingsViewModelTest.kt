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

package com.digiroth.smsfilter.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.lifecycle.viewModelScope
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.dao.StopListDao
import com.digiroth.smsfilter.data.db.entity.MatchMode
import com.digiroth.smsfilter.data.db.entity.OptOutPatternEntity
import com.digiroth.smsfilter.data.db.entity.ReplyType
import com.digiroth.smsfilter.data.db.entity.StopListEntity
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [SettingsViewModel], verifying pattern and stop-list mutations.
 *
 * Verifies that updating and adding opt-out patterns sanitizes input text, updates the DAO,
 * and ignores empty or whitespace-only patterns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var fakeContext: Context
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var secureTokenStore: SecureTokenStore
    private lateinit var contactRepository: ContactRepository
    private lateinit var hubSpotRepository: FakeHubSpotRepo
    private lateinit var healthEvaluator: ConnectionHealthEvaluator
    private lateinit var stopListDao: FakeStopListDao
    private lateinit var optOutPatternDao: RecordingOptOutPatternDao
    private var activeViewModel: SettingsViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("settings_vm_test").toFile()
        fakeContext = TestContext(tempDir)
        settingsDataStore = SettingsDataStore(fakeContext)
        secureTokenStore = SecureTokenStore(fakeContext)
        contactRepository = ContactRepository(fakeContext)
        hubSpotRepository = FakeHubSpotRepo()
        healthEvaluator = ConnectionHealthEvaluator()
        stopListDao = FakeStopListDao()
        optOutPatternDao = RecordingOptOutPatternDao()
    }

    @After
    fun tearDown() {
        activeViewModel?.viewModelScope?.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun createViewModel(): SettingsViewModel {
        val vm = SettingsViewModel(
            context = fakeContext,
            settingsDataStore = settingsDataStore,
            secureTokenStore = secureTokenStore,
            contactRepository = contactRepository,
            hubSpotRepository = hubSpotRepository,
            healthEvaluator = healthEvaluator,
            stopListDao = stopListDao,
            optOutPatternDao = optOutPatternDao,
        )
        activeViewModel = vm
        return vm
    }

    /**
     * Tests that updatePattern trims leading and trailing whitespace from pattern text before persisting to the DAO.
     *
     * Preconditions: Calling updatePattern with id=42, pattern="  stop2stop  ", STOP reply type, ANYWHERE match mode.
     * Expected: OptOutPatternDao receives an updated entity with trimmed pattern "stop2stop".
     */
    @Test
    fun `updatePattern trims pattern text and updates DAO`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.updatePattern(
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

        viewModel.updatePattern(
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

        viewModel.addPattern(
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

        viewModel.addPattern(
            pattern = "   ",
            replyType = ReplyType.STOP,
            matchMode = MatchMode.ANYWHERE,
        )
        advanceUntilIdle()

        assertTrue(optOutPatternDao.insertedPatterns.isEmpty())
    }

    private class RecordingOptOutPatternDao : OptOutPatternDao {
        val insertedPatterns: MutableList<OptOutPatternEntity> = mutableListOf()
        val updatedPatterns: MutableList<OptOutPatternEntity> = mutableListOf()
        val patternsFlow: MutableStateFlow<List<OptOutPatternEntity>> = MutableStateFlow(emptyList())

        override fun observeAll(): Flow<List<OptOutPatternEntity>> = patternsFlow
        override suspend fun getAll(): List<OptOutPatternEntity> = patternsFlow.value

        override suspend fun insert(entity: OptOutPatternEntity): Long {
            insertedPatterns += entity
            return 1L
        }

        override suspend fun insertAll(entities: List<OptOutPatternEntity>): List<Long> = emptyList()

        override suspend fun update(pattern: OptOutPatternEntity): Int {
            updatedPatterns += pattern
            return 1
        }

        override suspend fun delete(entity: OptOutPatternEntity): Unit = Unit
        override suspend fun count(): Int = patternsFlow.value.size
    }

    private class FakeStopListDao : StopListDao {
        override fun observeAll(): Flow<List<StopListEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<StopListEntity> = emptyList()
        override suspend fun insert(entity: StopListEntity): Long = 1L
        override suspend fun delete(entity: StopListEntity): Unit = Unit
        override suspend fun deleteByKeyword(keyword: String): Int = 1
        override suspend fun count(): Int = 0
    }

    private class FakeHubSpotRepo : HubSpotRepository {
        override suspend fun isKnownContact(e164Value: String?, rawDigits: String): ContactLookupOutcome =
            ContactLookupOutcome.NotFound

        override suspend fun testConnection(): ContactLookupOutcome = ContactLookupOutcome.NotFound
    }

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.digiroth.smsfilter"
        override fun getFilesDir(): File = baseDir
        override fun getDataDir(): File = baseDir
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
        override fun checkCallingOrSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        override fun getContentResolver(): ContentResolver? = null
    }
}
