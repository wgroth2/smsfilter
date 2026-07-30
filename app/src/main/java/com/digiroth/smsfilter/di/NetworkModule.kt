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

import com.digiroth.smsfilter.BuildConfig
import com.digiroth.smsfilter.data.remote.HubSpotApiService
import com.digiroth.smsfilter.data.remote.HubSpotAuthInterceptor
import com.digiroth.smsfilter.data.security.AccessTokenProvider
import com.digiroth.smsfilter.data.security.SecureAccessTokenProvider
import com.digiroth.smsfilter.data.settings.ConnectionStatusWriter
import com.digiroth.smsfilter.data.settings.DataStoreConnectionStatusWriter
import com.digiroth.smsfilter.util.AndroidLogger
import com.digiroth.smsfilter.util.AppLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the HubSpot networking stack.
 *
 * Generated in the same phase as the classes it references, following the project's rule that a
 * Hilt module never precedes its own bindings. Retrofit and OkHttp exist nowhere else in the app, so
 * this module has no reason to be loaded before the HubSpot layer.
 *
 * The two seam bindings live here rather than in `RepositoryModule` so that module needed only a
 * single line changed when the real HubSpot implementation replaced its placeholder.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * @return A Moshi instance. The reflective Kotlin adapter factory is registered as a fallback
     *   for any model that lacks a generated adapter; models carrying
     *   `@JsonClass(generateAdapter = true)` still use their KSP-generated adapters, which take
     *   precedence and require no reflection.
     */
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * @return A logging interceptor. Bodies are logged only in debug builds — a HubSpot response can
     *   contain customer contact details, and the release ProGuard configuration also strips the
     *   debug log calls that would print them.
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /**
     * @param authInterceptor Attaches the Private App token per request.
     * @param loggingInterceptor Debug-only traffic logging.
     * @return The shared OkHttp client.
     *
     * Timeouts are deliberately short. This client is called from an expedited worker with a limited
     * execution window, so a stalled HubSpot request must fail fast rather than hold the worker open
     * and risk the whole SMS lookup being killed mid-flight.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: HubSpotAuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    /**
     * @param client The shared OkHttp client.
     * @param moshi The shared Moshi instance.
     * @return Retrofit pointed at the HubSpot API root.
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(HubSpotApiService.BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    /**
     * @param retrofit The configured Retrofit instance.
     * @return The HubSpot endpoint implementation.
     */
    @Provides
    @Singleton
    fun provideHubSpotApiService(retrofit: Retrofit): HubSpotApiService =
        retrofit.create(HubSpotApiService::class.java)

    /**
     * @param impl Encrypted-storage-backed implementation.
     * @return The bound [AccessTokenProvider], which keeps the HubSpot repository free of any
     *   `Context`-requiring dependency and therefore JVM-testable.
     */
    @Provides
    @Singleton
    fun provideAccessTokenProvider(impl: SecureAccessTokenProvider): AccessTokenProvider = impl

    /**
     * @param impl Preferences-backed implementation.
     * @return The bound [ConnectionStatusWriter].
     */
    @Provides
    @Singleton
    fun provideConnectionStatusWriter(impl: DataStoreConnectionStatusWriter): ConnectionStatusWriter =
        impl

    /**
     * @param impl Framework-backed implementation.
     * @return The bound [AppLogger]. Injected rather than called statically so classes under JVM
     *   test can log without `android.util.Log` throwing "not mocked".
     */
    @Provides
    @Singleton
    fun provideAppLogger(impl: AndroidLogger): AppLogger = impl

    /** Connect, read, and write timeout for every HubSpot call, in seconds. */
    private const val TIMEOUT_SECONDS: Long = 5L
}
