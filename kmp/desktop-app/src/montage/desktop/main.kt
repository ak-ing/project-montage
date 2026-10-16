// SPDX-License-Identifier: Apache-2.0
package montage.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import montage.ui.MontageApp

fun main() {
    val storage = FileWorkspaceStorage(defaultWorkspaceFile())
    application {
        Window(onCloseRequest = ::exitApplication, title = "Project Montage") {
            MontageApp(System.getProperty("os.name"), storage)
        }
    }
}
