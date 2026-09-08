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

import android.Manifest
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
import com.digiroth.smsfilter.domain.hubspot.ConnectHubSpotUseCase
import com.digiroth.smsfilter.testutil.createTestSettingsDataStore
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
 * JVM unit tests for [SettingsViewModel].
 *
 * Pattern and stop-list coverage moved to `OptOutPatternsViewModelTest` and
 * `StopListViewModelTest` when those editors became their own destination; what is verified
 * here is the message-intake health this screen still derives.
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
    private var activeViewModel: SettingsViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("settings_vm_test").toFile()
        fakeContext = TestContext(tempDir)
        // See createTestSettingsDataStore's KDoc: this keeps the store's write actor on
        // testDispatcher instead of a real thread, so advanceUntilIdle() in tearDown() fully
        // drains it before resetMain() runs.
        settingsDataStore = SettingsDataStore(createTestSettingsDataStore(tempDir, testDispatcher))
        secureTokenStore = SecureTokenStore(fakeContext)
        contactRepository = ContactRepository(fakeContext)
        hubSpotRepository = FakeHubSpotRepo()
        healthEvaluator = ConnectionHealthEvaluator()
    }

    @After
    fun tearDown() {
        activeViewModel?.viewModelScope?.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun createViewModel(grantedPermissions: Set<String> = emptySet()): SettingsViewModel {
        (fakeContext as TestContext).grantedPermissions = grantedPermissions
        val vm = SettingsViewModel(
            context = fakeContext,
            settingsDataStore = settingsDataStore,
            secureTokenStore = secureTokenStore,
            contactRepository = contactRepository,
            hubSpotRepository = hubSpotRepository,
            connectHubSpotUseCase = ConnectHubSpotUseCase(
                secureTokenStore = secureTokenStore,
                hubSpotRepository = hubSpotRepository,
                settingsDataStore = settingsDataStore,
            ),
            healthEvaluator = healthEvaluator,
        )
        activeViewModel = vm
        return vm
    }

    private class FakeHubSpotRepo : HubSpotRepository {
        override suspend fun isKnownContact(e164Value: String?, rawDigits: String): ContactLookupOutcome =
            ContactLookupOutcome.NotFound

        override suspend fun testConnection(): ContactLookupOutcome = ContactLookupOutcome.NotFound
    }

    private class TestContext(
        private val baseDir: File,
        var grantedPermissions: Set<String> = emptySet(),
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.digiroth.smsfilter"
        override fun getFilesDir(): File = baseDir
        override fun getDataDir(): File = baseDir
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission in grantedPermissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (permission in grantedPermissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getContentResolver(): ContentResolver? = null
    }

    /**
     * Tests that refreshHealth evaluates message intake as DISABLED when RECEIVE_SMS permission is not held.
     *
     * Preconditions: RECEIVE_SMS permission is denied.
     * Expected: [SettingsUiState.messageIntakeHealth] is [MessageIntakeHealth.DISABLED].
     */
    @Test
    fun `refreshHealth evaluates message intake as disabled when sms permission is missing`() = runTest(testDispatcher) {
        val viewModel = createViewModel(grantedPermissions = emptySet())
        advanceUntilIdle()

        assertEquals(MessageIntakeHealth.DISABLED, viewModel.uiState.value.messageIntakeHealth)
    }

    /**
     * Tests that refreshHealth evaluates message intake as PARTIAL_SMS_ONLY when RECEIVE_SMS is held but notification access is missing.
     *
     * Preconditions: RECEIVE_SMS permission is granted; notification access is not granted.
     * Expected: [SettingsUiState.messageIntakeHealth] is [MessageIntakeHealth.PARTIAL_SMS_ONLY].
     */
    @Test
    fun `refreshHealth evaluates message intake as partial when sms held but notification access missing`() = runTest(testDispatcher) {
        val viewModel = createViewModel(grantedPermissions = setOf(Manifest.permission.RECEIVE_SMS))
        advanceUntilIdle()

        assertEquals(MessageIntakeHealth.PARTIAL_SMS_ONLY, viewModel.uiState.value.messageIntakeHealth)
    }
}
