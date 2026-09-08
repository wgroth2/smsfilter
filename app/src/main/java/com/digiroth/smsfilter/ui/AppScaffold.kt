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

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.digiroth.smsfilter.R

/**
 * The three peer destinations reachable from the bottom navigation bar.
 *
 * Settings is deliberately absent. It is reached from the Status app bar and pushed onto the back
 * stack, which is the platform convention and keeps the bar inside the 3–5 destinations Material 3
 * specifies for a [NavigationBar].
 *
 * Every icon here ships with Compose's bundled icon set, so the app needs no icon dependency.
 * Activity takes the bell rather than a list because Rules owns the list glyph, and two identical
 * icons in a three-item bar would be unreadable.
 *
 * @property route The navigation route this destination maps to.
 * @property labelRes Bottom-bar label.
 * @property icon Bottom-bar icon.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** Health dashboard, and the app's home. */
    STATUS(AppRoute.STATUS, R.string.nav_status, Icons.Default.Home),

    /** Activity and detection log. */
    ACTIVITY(AppRoute.ACTIVITY, R.string.nav_activity, Icons.Default.Notifications),

    /** Stop list and opt-out patterns. */
    RULES(AppRoute.RULES, R.string.nav_rules, Icons.AutoMirrored.Filled.List),
    ;

    companion object {
        /**
         * Whether a route is one of the three peer destinations.
         *
         * @param route The route to test, or `null` before the first destination resolves.
         * @return `true` when the bottom bar belongs on screen.
         */
        fun isTopLevel(route: String?): Boolean = entries.any { it.route == route }
    }
}

/**
 * Hosts the bottom navigation bar around the app's [NavHostController]-driven content.
 *
 * For official Android documentation on Navigation Compose and bottom navigation, see:
 * - Navigation: [https://developer.android.com/guide/navigation/design](https://developer.android.com/guide/navigation/design)
 *
 * The bar is rendered only while a [TopLevelDestination] is showing, so it is absent during the
 * onboarding wizard and on the pushed Settings screen. That absence is load-bearing for onboarding:
 * the wizard is the only writer of `firstRunComplete`, which gates the SMS pipeline, so a bar that
 * let the user navigate away mid-setup would leave the filter switched off while appearing ready.
 *
 * @param navController Controller whose current destination decides whether the bar is shown.
 * @param content Screen content, given the insets the bar leaves behind.
 */
@Composable
fun AppScaffold(
    navController: NavHostController,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        // Every destination hosts its own Scaffold, and each of those applies the system bar
        // insets. Leaving them on here as well would apply them twice, showing as a doubled gap
        // under the status bar. This outer Scaffold therefore contributes only the space its
        // bottom bar occupies.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        bottomBar = {
            if (TopLevelDestination.isTopLevel(currentDestination?.route)) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
        content = content,
    )
}

/**
 * Switches to a peer destination without stacking peers on top of one another.
 *
 * Pops to the graph's **start destination id** rather than to a hardcoded route: the start
 * destination is the single anchor every peer sits above, so popping to it keeps the back stack one
 * deep and leaves system Back meaning "return to Status, then leave the app". `saveState` and
 * `restoreState` preserve each tab's scroll position and filter selection across switches.
 *
 * @param destination The peer destination to show.
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
