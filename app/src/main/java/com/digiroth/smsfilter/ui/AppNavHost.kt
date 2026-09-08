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

package com.digiroth.smsfilter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.digiroth.smsfilter.ui.log.DetectionLogScreen
import com.digiroth.smsfilter.ui.onboarding.OnboardingScreen
import com.digiroth.smsfilter.ui.rules.RulesScreen
import com.digiroth.smsfilter.ui.settings.SettingsScreen
import com.digiroth.smsfilter.ui.status.StatusScreen

/** Navigation route names. */
object AppRoute {
    /** The first-run wizard. */
    const val ONBOARDING: String = "onboarding"

    /** The health dashboard, and the app's home. */
    const val STATUS: String = "status"

    /** The activity and detection log. */
    const val ACTIVITY: String = "activity"

    /** The stop list and opt-out patterns, as tabs. */
    const val RULES: String = "rules"

    /** Application settings; pushed from Status rather than a peer destination. */
    const val SETTINGS: String = "settings"
}

/**
 * The app's navigation graph.
 *
 * For official Android documentation on UDF architecture and Navigation Compose, see:
 * - Architecture: [https://developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * Nothing is rendered until the start destination is known. `firstRunComplete` arrives
 * asynchronously, and picking a default would show one screen and then replace it — visible as a
 * flash of the wizard for users who finished setup long ago.
 *
 * **The start destination never varies by launch intent.** An earlier design made a notification tap
 * start the graph at the log itself, which left nothing beneath it: `popUpTo(STATUS)` was a silent
 * no-op because Status had never been pushed, and system Back exited the app instead of returning to
 * the dashboard. A notification is now handled as a navigation *event* after composition, so Status
 * is genuinely underneath and Back behaves.
 *
 * @param requestedScreen The value of `NotificationRoute.EXTRA_OPEN_SCREEN` from the launching or
 *   most recent intent, or `null` for an ordinary launcher start.
 * @param onRequestedScreenHandled Invoked once the request has been navigated, so a recomposition
 *   or configuration change cannot replay the same tap.
 * @param viewModel Supplies the start destination.
 */
@Composable
fun AppNavHost(
    requestedScreen: String?,
    onRequestedScreenHandled: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val startDestination by viewModel.startDestination.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    when (startDestination) {
        null -> LoadingScreen()

        StartDestination.ONBOARDING -> AppScaffold(navController) { padding ->
            NavGraph(navController, AppRoute.ONBOARDING, Modifier.padding(padding))
        }

        StartDestination.MAIN -> {
            AppScaffold(navController) { padding ->
                NavGraph(navController, AppRoute.STATUS, Modifier.padding(padding))
            }

            // Deliberately not keyed on the NavController: this reacts to a new intent arriving,
            // and clearing the request afterwards is what stops it firing again on recomposition.
            LaunchedEffect(requestedScreen) {
                val target = NotificationRouteResolver.resolve(requestedScreen)
                    ?: return@LaunchedEffect

                navController.navigate(target) {
                    // Anchor on Status so Back from the opened screen returns to the dashboard
                    // rather than leaving the app.
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                onRequestedScreenHandled()
            }
        }
    }
}

/**
 * Builds the application [NavHost] destinations.
 *
 * @param navController The navigation controller managing app navigation.
 * @param startRoute The initial destination route.
 * @param modifier Applied to the host so bottom-bar insets reach every screen.
 */
@Composable
private fun NavGraph(
    navController: NavHostController,
    startRoute: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(AppRoute.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(AppRoute.STATUS) {
                        // The wizard must not remain on the back stack: pressing back from Status
                        // should leave the app, not re-enter setup that is already complete.
                        popUpTo(AppRoute.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(AppRoute.STATUS) {
            StatusScreen(
                onNavigateToSettings = { navController.navigate(AppRoute.SETTINGS) },
            )
        }
        composable(AppRoute.ACTIVITY) {
            // No back affordance: this is a peer destination, reached from the bottom bar.
            DetectionLogScreen()
        }
        composable(AppRoute.RULES) {
            RulesScreen()
        }
        composable(AppRoute.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Renders a full-screen loading spinner while initial preferences and start destination resolve.
 */
@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
