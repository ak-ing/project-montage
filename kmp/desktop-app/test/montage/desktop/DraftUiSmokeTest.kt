// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.toAwtImage
import montage.ui.MontageApp
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Runs a real ComposeWindow on the host, including its native Skiko renderer. */
class DraftUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editAppendAndResetDraft() {
        compose.setContent { MontageApp(System.getProperty("os.name")) }
        compose.onNodeWithTag("draft-summary").assertTextEquals("2 scenes · 10s visible · 1s transition buffer")
        compose.onNodeWithTag("add-scene").assertIsNotEnabled()
        capture("initial")
        compose.onNodeWithTag("project-title").performTextReplacement("UI smoke title")
        compose.onNodeWithTag("project-title").assertTextContains("UI smoke title")
        compose.onNodeWithTag("scene-description").performTextInput("Smoke scene")
        compose.onNodeWithTag("add-scene").assertIsEnabled().performClick()
        compose.onNodeWithTag("draft-summary").assertTextEquals("3 scenes · 14s visible · 2s transition buffer")
        compose.onNodeWithText("Smoke scene").assertExists()
        compose.onNodeWithTag("add-scene").assertIsNotEnabled()
        capture("added")
        compose.onNodeWithTag("reset-sample").performClick()
        compose.onNodeWithTag("draft-summary").assertTextEquals("2 scenes · 10s visible · 1s transition buffer")
        compose.onNodeWithTag("project-title").assertTextContains("My first Montage")
        compose.onNodeWithText("Smoke scene").assertDoesNotExist()
        capture("reset")
    }

    private fun capture(stage: String) {
        val output = File("../build/ui-smoke/desktop/$stage.png")
        output.parentFile.mkdirs()
        check(ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", output))
    }
}
