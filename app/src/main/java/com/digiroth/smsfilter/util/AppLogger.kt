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

package com.digiroth.smsfilter.util

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured logging behind an interface.
 *
 * `android.util.Log` is a framework class: in a JVM unit test every one of its methods throws
 * "Method … not mocked". That makes it impossible for a class to both satisfy the project's
 * structured-logging requirement — which mandates `Log` statements in repositories, workers, and
 * receivers — and be unit-testable on the JVM, unless the logging itself is injected.
 *
 * Classes that only ever run on a device may call `Log` directly. Classes covered by JVM tests take
 * this interface instead, and receive [NoOpLogger] in those tests.
 */
interface AppLogger {

    /**
     * Logs a debug message. Stripped from release builds by the R8 configuration.
     *
     * @param tag Class-level log tag.
     * @param message The message; must never contain a phone number or access token.
     */
    fun debug(tag: String, message: String)

    /**
     * Logs a recoverable problem.
     *
     * @param tag Class-level log tag.
     * @param message The message.
     * @param throwable Optional cause.
     */
    fun warn(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Logs a failure.
     *
     * @param tag Class-level log tag.
     * @param message The message.
     * @param throwable Optional cause.
     */
    fun error(tag: String, message: String, throwable: Throwable? = null)
}

/** The production [AppLogger], delegating to the Android framework logger. */
@Singleton
class AndroidLogger @Inject constructor() : AppLogger {

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }
}

/** An [AppLogger] that discards everything, for use in JVM unit tests. */
object NoOpLogger : AppLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}
