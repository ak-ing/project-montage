// SPDX-License-Identifier: Apache-2.0
import androidx.compose.ui.window.ComposeUIViewController
import montage.ui.MontageApp

fun ViewController(): platform.UIKit.UIViewController {
    val storage = IosWorkspaceStorage()
    return ComposeUIViewController { MontageApp("iOS", storage) }
}
