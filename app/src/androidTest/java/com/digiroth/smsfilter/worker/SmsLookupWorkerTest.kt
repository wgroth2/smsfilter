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

package com.digiroth.smsfilter.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.digiroth.smsfilter.data.repository.ContactLookupCache
import com.digiroth.smsfilter.data.repository.ContactLookupOutcome
import com.digiroth.smsfilter.detection.OptOutDetector
import com.digiroth.smsfilter.detection.StopListMatcher
import com.digiroth.smsfilter.util.MessageDeduplicator
import com.digiroth.smsfilter.util.PhoneNumberNormalizer
import com.digiroth.smsfilter.util.SenderHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [SmsLookupWorker], the Android adapter the JVM pipeline tests cannot
 * reach.
 *
 * The pipeline's decisions are already covered exhaustively by `SmsProcessingPipelineTest`; what is
 * verified here is specifically the glue: that input [Data] is unpacked into the right arguments,
 * that a real `CoroutineWorker` runs the pipeline to completion on a device, and that the outcome is
 * mapped onto the correct WorkManager [ListenableWorker.Result].
 *
 * A [WorkerFactory] supplies the worker with hand-built fakes, so no Hilt injection is involved and
 * `testInstrumentationRunner` stays at the default `AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class SmsLookupWorkerTest {

    private lateinit var context: Context
    private lateinit var fakes: PipelineFakes

    private val now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakes = PipelineFakes()
        fakes.time.now = now
    }

    /**
     * Builds the worker under test with a factory that hands it a pipeline wired to [fakes].
     *
     * @param input The worker's input data.
     * @return A real [SmsLookupWorker] ready to run on the device.
     */
    private fun buildWorker(input: Data): SmsLookupWorker {
        val pipeline = SmsProcessingPipeline(
            settings = fakes.settings,
            stopListDao = fakes.stopListDao,
            optOutPatternDao = fakes.patternDao,
            detectionLogDao = fakes.logDao,
            cooldownDao = fakes.cooldownDao,
            contactSource = fakes.contactSource,
            hubSpotRepository = fakes.hubSpot,
            contactLookupCache = ContactLookupCache(fakes.time),
            stopListMatcher = StopListMatcher(),
            optOutDetector = OptOutDetector(),
            phoneNumberNormalizer = PhoneNumberNormalizer(fakes.e164),
            senderHasher = SenderHasher(),
            smsSender = fakes.smsSender,
            directReplySender = fakes.directReplySender,
            messageDeduplicator = MessageDeduplicator(fakes.time),
            detectionNotifier = fakes.notifier,
            alertSoundPlayer = fakes.soundPlayer,
            timeProvider = fakes.time,
        )

        return TestListenableWorkerBuilder<SmsLookupWorker>(context)
            .setInputData(input)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SmsLookupWorker(appContext, workerParameters, pipeline)
                },
            )
            .build()
    }

    private fun inputFor(sender: String, body: String): Data = Data.Builder()
        .putString(SmsLookupWorker.KEY_SENDER_ADDRESS, sender)
        .putString(SmsLookupWorker.KEY_MESSAGE_BODY, body)
        .putLong(SmsLookupWorker.KEY_RECEIVED_AT, now)
        .build()

    /**
     * Tests end-to-end execution of [SmsLookupWorker] with valid opt-out input, verifying that an auto-reply is sent.
     *
     * Preconditions: Worker input containing standard phone number "+16505551234" and body "Hello\nSTOP".
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.success], SMS is sent to raw address, and reply status is logged.
     */
    @Test
    fun detectionRunsEndToEndAndRepliesToTheRawAddress() = runBlocking {
        val worker = buildWorker(inputFor(SENDER, "Hello\nSTOP"))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            "the reply must go to the raw originating address",
            listOf(SENDER to "stop"),
            fakes.smsSender.sent,
        )
        assertEquals("Reply sent: stop", fakes.logDao.inserted.single().replyStatus)
    }

    /**
     * Tests that [SmsLookupWorker] sends the reply to the exact short code address without normalization artifacts.
     *
     * Preconditions: Worker input containing short code "89887" and body "Hello\nSTOP".
     * Expected: Reply is dispatched to "89887".
     */
    @Test
    fun shortCodeReceivesReplyAtItsExactAddress() = runBlocking {
        // Guards the adapter against any normalization creeping in between Data and the pipeline.
        fakes.e164.result = "+189887"
        val worker = buildWorker(inputFor(SHORT_CODE, "Hello\nSTOP"))

        worker.doWork()

        assertEquals(listOf(SHORT_CODE to "stop"), fakes.smsSender.sent)
    }

    /**
     * Tests that non-detectable messages complete successfully via [SmsLookupWorker] without sending an SMS.
     *
     * Preconditions: Message body "Your appointment is confirmed".
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.success] and no SMS is sent.
     */
    @Test
    fun nonDetectionAlsoSucceedsWithoutSending() = runBlocking {
        val worker = buildWorker(inputFor(SENDER, "Your appointment is confirmed"))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    /**
     * Tests that the onboarding gate suppresses auto-reply when invoked via [SmsLookupWorker].
     *
     * Preconditions: Settings firstRunComplete is false.
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.success] without sending an SMS or logging.
     */
    @Test
    fun onboardingGateStillAppliesThroughTheWorker() = runBlocking {
        fakes.settings.snapshot = fakes.settings.snapshot.copy(firstRunComplete = false)
        val worker = buildWorker(inputFor(SENDER, "Hello\nSTOP"))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("nothing may be sent before onboarding completes", fakes.smsSender.sent.isEmpty())
        assertTrue(fakes.logDao.inserted.isEmpty())
    }

    /**
     * Tests that known contact ignore rules function correctly when triggered through [SmsLookupWorker].
     *
     * Preconditions: ContactSource returns Found for the sender.
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.success] and no SMS is sent.
     */
    @Test
    fun knownContactIsIgnoredThroughTheWorker() = runBlocking {
        fakes.contactSource.outcome = ContactLookupOutcome.Found
        val worker = buildWorker(inputFor(SENDER, "Hello\nSTOP"))

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    /**
     * Tests that missing sender address input in WorkManager [Data] results in permanent failure rather than retrying.
     *
     * Preconditions: Input data without KEY_SENDER_ADDRESS.
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.failure] and sends no SMS.
     */
    @Test
    fun missingSenderFailsPermanentlyRatherThanRetrying() = runBlocking {
        // Malformed input can never succeed on retry, so it must not consume the expedited quota.
        val input = Data.Builder()
            .putString(SmsLookupWorker.KEY_MESSAGE_BODY, "Hello\nSTOP")
            .build()
        val worker = buildWorker(input)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertTrue(fakes.smsSender.sent.isEmpty())
    }

    /**
     * Tests that missing message body input in WorkManager [Data] results in permanent failure.
     *
     * Preconditions: Input data without KEY_MESSAGE_BODY.
     * Expected: [SmsLookupWorker.doWork] returns [ListenableWorker.Result.failure].
     */
    @Test
    fun missingBodyFailsPermanently() = runBlocking {
        val input = Data.Builder()
            .putString(SmsLookupWorker.KEY_SENDER_ADDRESS, SENDER)
            .build()
        val worker = buildWorker(input)

        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    /**
     * Tests that [SmsLookupWorker.getForegroundInfo] supplies valid foreground notification metadata for expedited worker fallbacks.
     *
     * Preconditions: Instantiated worker with valid inputs.
     * Expected: Returned foreground info notification channel ID is non-empty.
     */
    @Test
    fun foregroundInfoIsAvailableForTheExpeditedFallback() = runBlocking {
        // Without this override WorkManager raises IllegalStateException when expedited work runs
        // as a foreground service on API < 31.
        val worker = buildWorker(inputFor(SENDER, "Hello\nSTOP"))

        val foregroundInfo = worker.getForegroundInfo()

        assertTrue(foregroundInfo.notification.channelId.isNotEmpty())
    }

    private companion object {
        const val SENDER = "+16505551234"
        const val SHORT_CODE = "89887"
    }
}
