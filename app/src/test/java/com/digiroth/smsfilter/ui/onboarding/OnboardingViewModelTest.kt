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

package com.digiroth.smsfilter.ui.onboarding

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.security.SecureTokenStore
import com.digiroth.smsfilter.data.settings.SettingsDataStore
import com.digiroth.smsfilter.domain.hubspot.ConnectHubSpotUseCase
import com.digiroth.smsfilter.domain.hubspot.HubSpotConnectError
import com.digiroth.smsfilter.ui.permissions.AppPermissions
import com.digiroth.smsfilter.ui.permissions.PermissionStateEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [OnboardingViewModel].
 *
 * Covers the four-step sequence introduced when Notification Access became its own step, the
 * non-blocking contract that step carries, and the optional HubSpot connection on the final step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File
    private lateinit var context: Context
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var hubSpotRepository: FakeHubSpotRepository
    private lateinit var contactRepository: com.digiroth.smsfilter.data.repository.ContactRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("onboarding_vm_test").toFile()
        context = TestContext(tempDir)
        settingsDataStore = SettingsDataStore(context)
        hubSpotRepository = FakeHubSpotRepository()
        contactRepository = com.digiroth.smsfilter.data.repository.ContactRepository(context).apply {
            // Keep the contacts query on the test scheduler; otherwise it outlives the test.
            queryDispatcher = testDispatcher
        }

        // `preferencesDataStore` is a property delegate that caches ONE DataStore per process,
        // whatever Context is handed to it, so every test in this JVM shares the same store and a
        // fresh temp directory does not isolate them. Reset the keys these tests assert on.
        runBlocking {
            settingsDataStore.setFirstRunComplete(false)
            settingsDataStore.setUseHubSpot(false)
            settingsDataStore.setHubSpotPromptShown(false)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    /**
     * Builds a ViewModel over the fakes created in [setUp].
     *
     * @return The ViewModel under test.
     */
    private fun createViewModel(): OnboardingViewModel {
        val secureTokenStore = SecureTokenStore(context)
        return OnboardingViewModel(
            settingsDataStore = settingsDataStore,
            contactRepository = contactRepository,
            permissionStateEvaluator = PermissionStateEvaluator(),
            connectHubSpotUseCase = ConnectHubSpotUseCase(
                secureTokenStore = secureTokenStore,
                hubSpotRepository = hubSpotRepository,
                settingsDataStore = settingsDataStore,
            ),
        )
    }

    /** Reports every blocking permission as granted, so the wizard can leave step 2. */
    private fun OnboardingViewModel.grantBlockingPermissions() {
        onPermissionFactsRefreshed(
            granted = AppPermissions.all().associateWith { true },
            shouldShowRationale = AppPermissions.all().associateWith { false },
        )
    }

    /**
     * Tests that the wizard reports four steps.
     *
     * Preconditions: none.
     * Expected: [OnboardingStep.COUNT] is 4, and the step indicator numbering runs 1 through 4.
     * The screens derive their "Step N of M" text from these, so a drift here is a visible bug.
     */
    @Test
    fun `wizard has four steps in order`() {
        assertEquals(4, OnboardingStep.COUNT)
        assertEquals(
            listOf(1, 2, 3, 4),
            OnboardingStep.entries.map { it.displayNumber },
        )
        assertEquals(
            listOf(
                OnboardingStep.WELCOME,
                OnboardingStep.PERMISSIONS,
                OnboardingStep.NOTIFICATION_ACCESS,
                OnboardingStep.CONNECTION_TEST,
            ),
            OnboardingStep.entries.toList(),
        )
    }

    /**
     * Tests that permissions lead to Notification Access, not straight to the final step.
     *
     * Preconditions: every blocking permission granted.
     * Expected: the wizard lands on [OnboardingStep.NOTIFICATION_ACCESS] — the step that exists to
     * stop users completing setup without MMS and RCS coverage.
     */
    @Test
    fun `permissions step advances to notification access`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.grantBlockingPermissions()

        viewModel.onPermissionsContinue()

        assertEquals(OnboardingStep.NOTIFICATION_ACCESS, viewModel.uiState.value.step)
    }

    /**
     * Tests that the permissions step still blocks while a required permission is missing.
     *
     * Preconditions: no permissions granted.
     * Expected: the step does not change.
     */
    @Test
    fun `permissions step blocks without required grants`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onPermissionFactsRefreshed(
            granted = AppPermissions.all().associateWith { false },
            shouldShowRationale = AppPermissions.all().associateWith { false },
        )
        viewModel.onResumeAtStep(OnboardingStep.PERMISSIONS)

        viewModel.onPermissionsContinue()

        assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.step)
    }

    /**
     * Tests that Notification Access does not block the wizard.
     *
     * Preconditions: Notification Access not granted.
     * Expected: continuing still reaches the final step. Blocking here would strand any user who
     * declines a Special App Access the app cannot grant on their behalf, and cellular SMS
     * filtering works without it.
     */
    @Test
    fun `notification access step advances even when not granted`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onResumeAtStep(OnboardingStep.NOTIFICATION_ACCESS)
        assertFalse(viewModel.uiState.value.isNotificationAccessGranted)

        viewModel.onNotificationAccessContinue()

        assertEquals(OnboardingStep.CONNECTION_TEST, viewModel.uiState.value.step)
    }

    /**
     * Tests that back navigation walks the new four-step chain in reverse.
     *
     * Preconditions: the wizard is on the final step.
     * Expected: back reaches Notification Access, then Permissions, then Welcome, and stops there
     * rather than underflowing.
     */
    @Test
    fun `back navigation walks every step in reverse`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onResumeAtStep(OnboardingStep.CONNECTION_TEST)

        viewModel.onBack()
        assertEquals(OnboardingStep.NOTIFICATION_ACCESS, viewModel.uiState.value.step)
        viewModel.onBack()
        assertEquals(OnboardingStep.PERMISSIONS, viewModel.uiState.value.step)
        viewModel.onBack()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
        viewModel.onBack()
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
    }

    /**
     * Tests that a resumed wizard with permissions already held lands on Notification Access.
     *
     * Preconditions: every blocking permission granted, mid-wizard restart.
     * Expected: [OnboardingStep.NOTIFICATION_ACCESS]. Nothing records whether it was already
     * offered, so resuming there is the safe direction — an extra look at an optional step beats
     * silently skipping the one that gates MMS and RCS.
     */
    @Test
    fun `resume lands on notification access when permissions are held`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.grantBlockingPermissions()

        assertEquals(OnboardingStep.NOTIFICATION_ACCESS, viewModel.resolveResumeStep())
    }

    /**
     * Tests that a resumed wizard without permissions returns to the permissions step.
     *
     * Preconditions: no permissions granted.
     * Expected: [OnboardingStep.PERMISSIONS].
     */
    @Test
    fun `resume returns to permissions when grants are missing`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.onPermissionFactsRefreshed(
            granted = AppPermissions.all().associateWith { false },
            shouldShowRationale = AppPermissions.all().associateWith { false },
        )

        assertEquals(OnboardingStep.PERMISSIONS, viewModel.resolveResumeStep())
    }

    /**
     * Tests that onDone is the only writer of the pipeline's onboarding gate.
     *
     * Preconditions: a fresh wizard, `firstRunComplete` unset.
     * Expected: the flag is false through every step transition and becomes true only after
     * [OnboardingViewModel.onDone]. The SMS pipeline reads this flag to decide whether to act on an
     * incoming message at all, so an early write would let the app reply before setup finished.
     */
    @Test
    fun `first run completes only on done`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        viewModel.grantBlockingPermissions()

        viewModel.onGetStarted()
        viewModel.onPermissionsContinue()
        viewModel.onNotificationAccessContinue()
        advanceUntilIdle()
        assertFalse(settingsDataStore.firstRunComplete.first())

        viewModel.onDone()

        // Awaited rather than sampled: DataStore commits on its own IO scope, so the write is not
        // complete when the test scheduler goes idle.
        assertTrue(settingsDataStore.firstRunComplete.first { it })
        assertTrue(viewModel.uiState.first { it.isFinished }.isFinished)
    }

    /**
     * Tests that a successful optional HubSpot connect switches the integration on.
     *
     * Preconditions: HubSpot answers the verification call.
     * Expected: the state reports connected with no error, and `useHubSpot` is enabled — connecting
     * during the wizard should not leave the toggle off afterwards.
     */
    @Test
    fun `successful hubspot connect enables the integration`() = runTest(testDispatcher) {
        hubSpotRepository.outcome = ContactLookupOutcome.NotFound
        val viewModel = createViewModel()

        viewModel.onConnectHubSpot("pat-na1-token")

        val state = viewModel.uiState.first { it.hubSpotConnected }
        assertNull(state.hubSpotError)
        assertTrue(settingsDataStore.useHubSpot.first { it })
        // Connecting during the wizard answers the one-time Settings prompt's question, so that
        // prompt must be marked shown — otherwise the first Settings visit asks the user to
        // connect an integration that is already connected.
        assertTrue(settingsDataStore.hubSpotPromptShown.first { it })
    }

    /**
     * Tests that a rejected token surfaces inline without blocking the wizard.
     *
     * Preconditions: HubSpot rejects the token as unauthorized.
     * Expected: an [HubSpotConnectError.INVALID_TOKEN] on screen, the integration left off, and the
     * wizard still finishable — HubSpot is optional and must never trap the user on this step.
     */
    @Test
    fun `rejected hubspot token surfaces an error and never blocks done`() = runTest(testDispatcher) {
        hubSpotRepository.outcome =
            ContactLookupOutcome.Failed(HubSpotRepositoryImpl.REASON_UNAUTHORIZED)
        val viewModel = createViewModel()

        viewModel.onConnectHubSpot("bad-token")

        val state = viewModel.uiState.first { it.hubSpotError != null }
        assertEquals(HubSpotConnectError.INVALID_TOKEN, state.hubSpotError)
        assertFalse(state.hubSpotConnected)
        assertFalse(settingsDataStore.useHubSpot.first())

        // HubSpot is optional: a rejected token must never trap the user on this step.
        viewModel.onDone()
        assertTrue(settingsDataStore.firstRunComplete.first { it })
    }

    /** A [HubSpotRepository] returning a caller-supplied outcome. */
    private class FakeHubSpotRepository : HubSpotRepository {
        /** The outcome [testConnection] returns. */
        var outcome: ContactLookupOutcome = ContactLookupOutcome.NotFound

        override suspend fun isKnownContact(
            e164Value: String?,
            rawDigits: String,
        ): ContactLookupOutcome = ContactLookupOutcome.NotFound

        override suspend fun testConnection(): ContactLookupOutcome = outcome
    }

    /** Minimal [Context] giving DataStore a writable directory on the JVM. */
    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.digiroth.smsfilter"
        override fun getFilesDir(): File = baseDir
        override fun getDataDir(): File = baseDir
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_DENIED

        override fun checkCallingOrSelfPermission(permission: String): Int =
            PackageManager.PERMISSION_DENIED

        override fun getContentResolver(): ContentResolver? = null
    }
}
