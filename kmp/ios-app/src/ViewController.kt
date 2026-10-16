// SPDX-License-Identifier: Apache-2.0
import androidx.compose.ui.window.ComposeUIViewController
import montage.ui.MontageApp

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun ViewController(): platform.UIKit.UIViewController {
    val storage = IosWorkspaceStorage()
    lateinit var controller: platform.UIKit.UIViewController
    val videos = IosVideoLibrary { controller.presentViewController(it, true, null) }
    controller = ComposeUIViewController { MontageApp("iOS", storage, videos) }
    return controller
}
