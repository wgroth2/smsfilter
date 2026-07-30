/*
 * Copyright (c) 2025 Bill Roth <bill.roth@gmail.com>
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

package com.digiroth.smsfilter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.digiroth.smsfilter.platform.NotificationRoute
import com.digiroth.smsfilter.ui.log.DetectionLogScreen
import com.digiroth.smsfilter.ui.onboarding.OnboardingScreen
import com.digiroth.smsfilter.ui.settings.SettingsScreen

/** Navigation route names. */
object AppRoute {
    /** The first-run wizard. */
    const val ONBOARDING: String = "onboarding"

    /** The settings screen, which is the app's home. */
    const val SETTINGS: String = "settings"

    /** The activity and detection log. */
    const val DETECTION_LOG: String = "detection_log"
}

/**
 * The app's navigation graph.
 *
 * Nothing is rendered until the start destination is known. `firstRunComplete` arrives
 * asynchronously, and picking a default would show one screen and then replace it — visible as a
 * flash of the wizard for users who finished setup long ago.
 *
 * @param requestedScreen The value of `NotificationRoute.EXTRA_OPEN_SCREEN` from the launching
 *   intent, or `null` for an ordinary launcher start.
 * @param viewModel Supplies the start destination.
 */
@Composable
fun AppNavHost(
    requestedScreen: String?,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val startDestination by viewModel.startDestination.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (startDestination) {
        null -> LoadingScreen()

        StartDestination.ONBOARDING -> NavGraph(
            navController = navController,
            startRoute = AppRoute.ONBOARDING,
        )

        StartDestination.MAIN -> NavGraph(
            navController = navController,
            // A notification tap routes straight to the screen it refers to. Ordinary launcher
            // starts land on Settings — never on a hidden or backgrounded task, which would leave
            // the user unable to reach Settings at all.
            startRoute = when (requestedScreen) {
                NotificationRoute.SCREEN_DETECTION_LOG -> AppRoute.DETECTION_LOG
                else -> AppRoute.SETTINGS
            },
        )
    }
}

@Composable
private fun NavGraph(navController: NavHostController, startRoute: String) {
    NavHost(navController = navController, startDestination = startRoute) {
        composable(AppRoute.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(AppRoute.SETTINGS) {
                        // The wizard must not remain on the back stack: pressing back from Settings
                        // should leave the app, not re-enter setup that is already complete.
                        popUpTo(AppRoute.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(AppRoute.SETTINGS) {
            SettingsScreen(
                onNavigateToLog = { navController.navigate(AppRoute.DETECTION_LOG) },
            )
        }
        composable(AppRoute.DETECTION_LOG) {
            DetectionLogScreen(
                // popBackStack returns false when the log is the start destination, which happens
                // when the screen was opened straight from a notification tap. Fall back to
                // navigating to Settings so the button is never a dead control.
                onNavigateBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(AppRoute.SETTINGS)
                    }
                },
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
