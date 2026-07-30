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

import android.content.Context
import androidx.room.Room
import com.digiroth.smsfilter.data.db.AppDatabase
import com.digiroth.smsfilter.data.db.dao.AutoReplyCooldownDao
import com.digiroth.smsfilter.data.db.dao.DetectionLogDao
import com.digiroth.smsfilter.data.db.dao.OptOutPatternDao
import com.digiroth.smsfilter.data.db.dao.StopListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room database and its DAOs.
 *
 * This is the project's first Hilt module, generated in the same phase as the Room classes it
 * references — modules are deliberately co-located with their dependencies so that no phase
 * ever compiles a module whose bindings do not yet exist.
 *
 * `SettingsDataStore` and `SecureTokenStore` are intentionally absent: both use `@Inject`
 * constructors and are therefore constructible by Hilt without a module.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Builds the singleton Room database.
     *
     * `fallbackToDestructiveMigration(dropAllTables = true)` recreates the schema instead of
     * requiring hand-written migrations. That is an accepted trade-off during development —
     * a schema change discards the user's stop list, custom patterns, and log history — and
     * is why no schema is exported.
     *
     * [AppDatabase.SEED_CALLBACK] must be attached here, since Room invokes creation callbacks
     * only through the builder; without it a fresh install would start with no opt-out
     * patterns and would never detect anything.
     *
     * @param context Application context owning the database file.
     * @return The process-wide [AppDatabase] instance.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addCallback(AppDatabase.SEED_CALLBACK)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    /**
     * @param database The application database.
     * @return Data access for stop-list keywords.
     */
    @Provides
    fun provideStopListDao(database: AppDatabase): StopListDao = database.stopListDao()

    /**
     * @param database The application database.
     * @return Data access for opt-out patterns.
     */
    @Provides
    fun provideOptOutPatternDao(database: AppDatabase): OptOutPatternDao = database.optOutPatternDao()

    /**
     * @param database The application database.
     * @return Data access for the activity and detection log.
     */
    @Provides
    fun provideDetectionLogDao(database: AppDatabase): DetectionLogDao = database.detectionLogDao()

    /**
     * @param database The application database.
     * @return Data access for auto-reply cooldown records.
     */
    @Provides
    fun provideAutoReplyCooldownDao(database: AppDatabase): AutoReplyCooldownDao =
        database.autoReplyCooldownDao()
}
