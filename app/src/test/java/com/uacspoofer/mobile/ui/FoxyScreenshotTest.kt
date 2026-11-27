/*
 * Renders the real Compose screens on the JVM (Robolectric + Roborazzi) so the UI can be
 * inspected without a device. Only used for development screenshots.
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.ui.theme.UacSniSpooferTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
class FoxyScreenshotTest {
    @get:Rule
    val rule: ComposeContentTestRule = createComposeRule()

    private fun render(name: String, persian: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalHomePersian provides persian) {
                UacSniSpooferTheme {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(UacColors.BackgroundTop),
                    ) { content() }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun foxySettingsEnglish() {
        render("foxy_settings_en", persian = false) { FoxySettingsScreen(onBack = {}) }
    }

    @Test
    fun foxySettingsPersian() {
        render("foxy_settings_fa", persian = true) { FoxySettingsScreen(onBack = {}) }
    }

    @Test
    fun foxyLivePathEnglish() {
        // A representative mid-connect state so the colours/dots are visible.
        com.uacspoofer.mobile.engine.foxy.FoxyStatusStore.update(
            com.uacspoofer.mobile.engine.foxy.FoxyPhase.STARTING,
            10,
            "Preparing tunnel...",
        )
        com.uacspoofer.mobile.engine.foxy.FoxyStatusStore.update(
            com.uacspoofer.mobile.engine.foxy.FoxyPhase.AUTH,
            30,
            "Trading the Firefox session token for a proxy pass",
        )
        com.uacspoofer.mobile.engine.foxy.FoxyStatusStore.update(
            com.uacspoofer.mobile.engine.foxy.FoxyPhase.EDGE,
            62,
            "Connecting to Fastly edge (muc139.m1.fastly-masque.net:2499)...",
            edgeHost = "muc139.m1.fastly-masque.net",
        )
        render("foxy_live_path_en", persian = false) {
            Box(Modifier.fillMaxSize().padding(16.dp)) { FoxyLiveFlowContent() }
        }
    }

    @Test
    fun foxyCountriesEnglish() {
        rule.setContent {
            CompositionLocalProvider(LocalHomePersian provides false) {
                UacSniSpooferTheme {
                    Box(Modifier.fillMaxSize().background(UacColors.BackgroundTop)) {
                        HomeFoxyCountryDialog(visible = true, connected = false, onDismissRequest = {})
                    }
                }
            }
        }
        // The dialog loads Mozilla's list on IO; wait for rows, then fall back is already bundled.
        runCatching {
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithText("Germany", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/foxy_countries_en.png")
    }

    @Test
    fun foxyCountriesPersian() {
        rule.setContent {
            CompositionLocalProvider(LocalHomePersian provides true) {
                UacSniSpooferTheme {
                    Box(Modifier.fillMaxSize().background(UacColors.BackgroundTop)) {
                        HomeFoxyCountryDialog(visible = true, connected = false, onDismissRequest = {})
                    }
                }
            }
        }
        runCatching {
            rule.waitUntil(timeoutMillis = 15_000) {
                rule.onAllNodesWithText("آلمان", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/foxy_countries_fa.png")
    }

    @Test
    fun foxyLivePathPersian() {
        render("foxy_live_path_fa", persian = true) {
            Box(Modifier.fillMaxSize().padding(16.dp)) { FoxyLiveFlowContent() }
        }
    }
}
