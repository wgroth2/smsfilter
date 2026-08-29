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

package com.digiroth.smsfilter

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.digiroth.smsfilter.platform.NotificationRoute
import com.digiroth.smsfilter.ui.AppNavHost
import com.digiroth.smsfilter.ui.theme.SmsFilterTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single Activity.
 *
 * Implements Modern Android Architecture (UDF) and Navigation Compose.
 * See:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Must live in this package: `AndroidManifest.xml` declares `android:name=".MainActivity"` with the
 * launcher intent filter, so moving it would produce a `ClassNotFoundException` at launch.
 *
 * Extends `AppCompatActivity` rather than `ComponentActivity` because per-app locale switching on API
 * levels below 33 goes through `AppCompatDelegate`, which requires an AppCompat host. The language
 * selector is built in the next phase, and choosing the lighter base class now would force this file
 * to change then.
 *
 * `moveTaskToBack` is deliberately never called. Sending the task to the background on a launcher
 * start would leave the user unable to reach Settings, the Stop List, or the log except by way of a
 * notification.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /**
     * Initializes the activity, reads any initial navigation routing extras from the launching intent,
     * and sets the Compose content hierarchy.
     *
     * @param savedInstanceState Saved instance state bundle, or `null` if freshly launched.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read once at creation: this is the intent that launched the activity, and a later
        // recomposition must not re-navigate on the same extra.
        val requestedScreen = intent?.getStringExtra(NotificationRoute.EXTRA_OPEN_SCREEN)

        setContent {
            SmsFilterTheme {
                AppNavHost(requestedScreen = requestedScreen)
            }
        }
    }
}
