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

package com.digiroth.smsfilter.di

import com.digiroth.smsfilter.data.repository.ContactSource
import com.digiroth.smsfilter.data.repository.GoogleContactSource
import com.digiroth.smsfilter.data.repository.HubSpotRepository
import com.digiroth.smsfilter.data.repository.HubSpotRepositoryImpl
import com.digiroth.smsfilter.data.settings.DataStoreSettingsSnapshotProvider
import com.digiroth.smsfilter.data.settings.SettingsSnapshotProvider
import com.digiroth.smsfilter.platform.AlertSoundPlayer
import com.digiroth.smsfilter.platform.AndroidAlertSoundPlayer
import com.digiroth.smsfilter.platform.AndroidDetectionNotifier
import com.digiroth.smsfilter.platform.AndroidSmsSender
import com.digiroth.smsfilter.platform.DetectionNotifier
import com.digiroth.smsfilter.platform.SmsSender
import com.digiroth.smsfilter.util.SystemTimeProvider
import com.digiroth.smsfilter.util.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the SMS pipeline's abstractions to their production implementations.
 *
 * `ContactRepository`, `ContactLookupCache`, `SmsProcessingPipeline`, and the detection classes are
 * absent by design: each has an `@Inject` constructor, so Hilt constructs them without a binding.
 * Only interfaces need declaring here.
 *
 * [HubSpotRepository] is bound to the no-op placeholder for now so the Google-Contacts-only path
 * works end to end; the HubSpot API phase replaces that one line with the real implementation.
 * Retrofit, OkHttp, and Moshi are not wired anywhere yet, so there is deliberately no
 * `NetworkModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * @param impl The production HubSpot implementation. NoOpHubSpotRepository remains in the
     *   codebase but is no longer bound.
     * @return The bound [HubSpotRepository].
     */
    @Binds
    @Singleton
    abstract fun bindHubSpotRepository(impl: HubSpotRepositoryImpl): HubSpotRepository

    /**
     * @param impl Platform SMS implementation.
     * @return The bound [SmsSender].
     */
    /**
     * @param impl Adapter over the DataStore-backed settings store.
     * @return The bound [SettingsSnapshotProvider], which keeps the pipeline free of any
     *   `Context`-requiring dependency and therefore JVM-testable.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsSnapshotProvider(
        impl: DataStoreSettingsSnapshotProvider,
    ): SettingsSnapshotProvider

    /**
     * @param impl Adapter over the Google Contacts repository.
     * @return The bound [ContactSource]. `ContactRepository` remains available directly as its
     *   concrete type for the Settings screen's permission and count queries.
     */
    @Binds
    @Singleton
    abstract fun bindContactSource(impl: GoogleContactSource): ContactSource

    @Binds
    @Singleton
    abstract fun bindSmsSender(impl: AndroidSmsSender): SmsSender

    /**
     * @param impl Platform notification implementation.
     * @return The bound [DetectionNotifier].
     */
    @Binds
    @Singleton
    abstract fun bindDetectionNotifier(impl: AndroidDetectionNotifier): DetectionNotifier

    /**
     * @param impl Platform ringtone implementation.
     * @return The bound [AlertSoundPlayer].
     */
    @Binds
    @Singleton
    abstract fun bindAlertSoundPlayer(impl: AndroidAlertSoundPlayer): AlertSoundPlayer

    /**
     * @param impl System-clock implementation.
     * @return The bound [TimeProvider].
     */
    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
