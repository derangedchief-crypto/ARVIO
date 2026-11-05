package com.extreme.myhub

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native rendering checks using sample data; no production account or API calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dashboardNavigationAndRendering() {
        var destination = ""
        val data = JSONObject().put("connected", 3).put("total", 14).put("customers", 8)
        compose.setContent {
            HubTheme {
                WorkspaceFrame(HubState(busy = false, data = data), { destination = it }, {}, {}) {
                    Dashboard(data, "Preview", false, onlineDevices = { destination = "online-only" }) { destination = it }
                }
            }
        }
        compose.onNodeWithText("Everything, connected.").assertIsDisplayed()
        compose.onNodeWithText("Connected devices").assertHasClickAction().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 10f, height / 2f))
        }
        org.junit.Assert.assertEquals("online-only", destination)
        compose.onNodeWithText("View online devices").performClick()
        org.junit.Assert.assertEquals("online-only", destination)
        compose.onNodeWithText("Total devices").performClick()
        org.junit.Assert.assertEquals("devices", destination)
        compose.onNodeWithText("More").performClick()
        org.junit.Assert.assertEquals("more", destination)
        capture("home-phone")
    }

    @Test fun loginRendersWithoutAccount() {
        compose.setContent { HubTheme { Surface(Modifier.fillMaxSize(), color = HubColors.background) { Column(Modifier.padding(horizontal = 24.dp)) { Login(false) { _, _ -> } } } } }
        compose.onNodeWithText("Sign in to your workspace").assertIsNotEnabled()
        compose.onNodeWithText("Reseller username").performTextInput("Preview")
        compose.onNodeWithText("Password").performTextInput("preview-only")
        compose.onNodeWithText("Sign in to your workspace").assertIsEnabled()
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        compose.onNodeWithContentDescription("Hide password").performClick()
        capture("login-phone")
    }

    @Test
    @Config(qualifiers = "w800dp-h1024dp-xhdpi")
    fun tabletRailKeepsNavigationVisible() {
        var destination = ""
        compose.setContent {
            HubTheme {
                WorkspaceFrame(HubState(busy = false), { destination = it }, {}, {}) {
                    Dashboard(JSONObject().put("total", 0), "Preview", false, onlineDevices = { destination = "online-only" }) { destination = it }
                }
            }
        }
        compose.onNodeWithText("More").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals("more", destination)
        compose.onNodeWithText("Everything, connected.").assertIsDisplayed()
        capture("home-tablet")
    }

    @Test fun deviceListShowsLivePresenceAndExpandableFilters() {
        val model = HubModel(compose.activity.application)
        val row = JSONObject().put("id", "a".repeat(32)).put("label", "Living Room TV")
            .put("customer_name", "Preview Customer").put("presence", "online").put("policy", "active")
        val state = HubState(busy = false, route = "devices", data = JSONObject().put("items", org.json.JSONArray().put(row)))
        compose.setContent { HubTheme { WorkspaceFrame(state, {}, {}, {}) { Records(state, model) } } }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Access & history filters").performClick()
        compose.onNodeWithText("Device history").assertIsDisplayed()
        compose.onNodeWithText("Hide filters").performClick()
        capture("devices-phone")
    }

    @Test fun customerCardsShowVerifiedNamesInsteadOfPanelReferences() {
        val model = HubModel(compose.activity.application)
        val row = JSONObject().put("id", "a".repeat(32)).put("name", "Panel line 946")
            .put("external_reference", "panel:946").put("line_username", "sam.taylor")
        val state = HubState(busy = false, route = "customers", data = JSONObject().put("items", org.json.JSONArray().put(row)))
        compose.setContent { HubTheme { WorkspaceFrame(state, {}, {}, {}) { Records(state, model) } } }
        compose.onNodeWithText("sam.taylor").assertIsDisplayed()
        compose.onNodeWithText("Panel line 946").assertDoesNotExist()
        compose.onNodeWithText("panel:946").assertDoesNotExist()
        capture("customers-verified-name-phone")
    }

    @Test fun renewalsSwitchSectionsWithoutLosingNavigation() {
        val model = HubModel(compose.activity.application)
        val state = HubState(busy = false, route = "renewals/settings", data = JSONObject())
        compose.setContent { HubTheme { WorkspaceFrame(state, {}, {}, {}) { Renewals(state, model) } } }
        compose.onNodeWithText("No matching payments").assertIsDisplayed()
        capture("renewals-phone")
        compose.onNodeWithText("Pricing").performClick()
        compose.onNodeWithText("Trial conversion").assertIsDisplayed()
        compose.onNodeWithText("Home").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val directory = File("build/ui-preview").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
