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

package com.digiroth.smsfilter.ui.status

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.entity.DetectionLogEntity
import com.digiroth.smsfilter.data.db.entity.LogEventType
import com.digiroth.smsfilter.data.repository.ContactRepository
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import com.digiroth.smsfilter.ui.settings.ConnectionHealthEvaluator
import com.digiroth.smsfilter.ui.settings.GoogleContactsHealth
import com.digiroth.smsfilter.ui.settings.MessageIntakeHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [StatusViewModel].
 *
 * Covers the intake and contacts indicators, and the recent-activity summary the dashboard shows.
 *
 * **Not covered here, deliberately:** the HubSpot indicator. Deriving it needs
 * [SecureTokenStore.hasAccessToken], and `EncryptedSharedPreferences` cannot open without the
 * Android Keystore, so under a plain JVM test the store always reports "no token" regardless of
 * what production code did. `ConnectionHealthEvaluatorTest` covers the four-state rule itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var context: TestContext
    private lateinit var detectionLogDao: FakeDetectionLogDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("status_vm_test").toFile()
        context = TestContext(tempDir)
        detectionLogDao = FakeDetectionLogDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    /**
     * Builds a ViewModel over the fakes created in [setUp].
     *
     * @param grantedPermissions Permissions the fake context reports as held.
     * @return The ViewModel under test.
     */
    private fun createViewModel(grantedPermissions: Set<String> = emptySet()): StatusViewModel {
        context.grantedPermissions = grantedPermissions
        return StatusViewModel(
            context = context,
            settingsDataStore = SettingsDataStore(context),
            secureTokenStore = SecureTokenStore(context),
            contactRepository = ContactRepository(context),
            healthEvaluator = ConnectionHealthEvaluator(),
            detectionLogDao = detectionLogDao,
        )
    }

    /**
     * Tests that missing RECEIVE_SMS reports intake as disabled.
     *
     * Preconditions: no permissions granted.
     * Expected: [MessageIntakeHealth.DISABLED] — nothing can be filtered at all.
     */
    @Test
    fun `missing sms permission reports intake disabled`() = runTest(testDispatcher) {
        val viewModel = createViewModel(grantedPermissions = emptySet())
        advanceUntilIdle()

        assertEquals(MessageIntakeHealth.DISABLED, viewModel.uiState.value.messageIntakeHealth)
    }

    /**
     * Tests that SMS access without Notification Access reports partial intake.
     *
     * Preconditions: RECEIVE_SMS granted, Notification Access absent.
     * Expected: [MessageIntakeHealth.PARTIAL_SMS_ONLY] — the state this redesign exists to surface,
     * where cellular SMS is filtered but MMS and RCS silently are not.
     */
    @Test
    fun `sms without notification access reports partial intake`() = runTest(testDispatcher) {
        val viewModel = createViewModel(grantedPermissions = setOf(Manifest.permission.RECEIVE_SMS))
        advanceUntilIdle()

        assertEquals(
            MessageIntakeHealth.PARTIAL_SMS_ONLY,
            viewModel.uiState.value.messageIntakeHealth,
        )
    }

    /**
     * Tests that a denied contacts permission surfaces on the contacts indicator.
     *
     * Preconditions: READ_CONTACTS not granted.
     * Expected: [GoogleContactsHealth.PERMISSION_REQUIRED].
     */
    @Test
    fun `denied contacts permission reports permission required`() = runTest(testDispatcher) {
        val viewModel = createViewModel(grantedPermissions = emptySet())
        advanceUntilIdle()

        assertEquals(
            GoogleContactsHealth.PERMISSION_REQUIRED,
            viewModel.uiState.value.googleContactsHealth,
        )
    }

    /**
     * Tests that the evaluated-today figure comes from the DAO.
     *
     * Preconditions: the DAO reports 12 rows since the day boundary.
     * Expected: the state carries 12.
     */
    @Test
    fun `evaluated today reflects the dao count`() = runTest(testDispatcher) {
        detectionLogDao.countSince.value = 12

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(12, viewModel.uiState.value.evaluatedToday)
    }

    /**
     * Tests that the last detection is surfaced when one exists.
     *
     * Preconditions: the DAO holds a DETECTION entry.
     * Expected: that entry appears in state, so the card can name what was replied to.
     */
    @Test
    fun `last detection is surfaced from the dao`() = runTest(testDispatcher) {
        val entry = DetectionLogEntity(
            id = 3,
            timestamp = 1_700_000_000_000,
            eventType = LogEventType.DETECTION,
            messagePreview = "reply stop2quit to end",
            replyStatus = "Reply sent: stop",
        )
        detectionLogDao.latestDetection.value = entry

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(entry, viewModel.uiState.value.lastDetection)
    }

    /**
     * Tests that an empty log leaves the last-detection slot null.
     *
     * Preconditions: the DAO holds no detections.
     * Expected: `null`, so the card renders "no opt-out detected yet" rather than a blank line.
     */
    @Test
    fun `absent detection leaves last detection null`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.lastDetection)
    }

    /** A [DetectionLogDao] whose Status-facing queries are driven by the test. */
    private class FakeDetectionLogDao : DetectionLogDao {
        /** Value returned by [observeCountSince]. */
        val countSince: MutableStateFlow<Int> = MutableStateFlow(0)

        /** Value returned by [observeLatestByType]. */
        val latestDetection: MutableStateFlow<DetectionLogEntity?> = MutableStateFlow(null)

        override fun observeCountSince(since: Long): Flow<Int> = countSince
        override fun observeLatestByType(eventType: LogEventType): Flow<DetectionLogEntity?> =
            latestDetection

        override fun observeAll(limit: Int): Flow<List<DetectionLogEntity>> = flowOf(emptyList())
        override fun observeRecentActionable(
            limit: Int,
            excludedType: LogEventType,
        ): Flow<List<DetectionLogEntity>> = flowOf(emptyList())

        override fun observeRecentByType(
            eventType: LogEventType,
            limit: Int,
        ): Flow<List<DetectionLogEntity>> = flowOf(emptyList())

        override suspend fun getRecent(limit: Int): List<DetectionLogEntity> = emptyList()
        override suspend fun insert(entity: DetectionLogEntity): Long = 1L
        override suspend fun clear(): Int = 0
        override suspend fun count(): Int = 0
    }

    /** Minimal [Context] giving DataStore a writable directory and controllable grants. */
    private class TestContext(
        private val baseDir: File,
        var grantedPermissions: Set<String> = emptySet(),
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.digiroth.smsfilter"
        override fun getFilesDir(): File = baseDir
        override fun getDataDir(): File = baseDir
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission in grantedPermissions) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }

        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (permission in grantedPermissions) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }

        override fun getContentResolver(): ContentResolver? = null
    }
}
