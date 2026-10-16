// SPDX-License-Identifier: Apache-2.0
import androidx.compose.ui.window.ComposeUIViewController
import montage.ui.MontageApp

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun ViewController(): platform.UIKit.UIViewController {
    val storage = IosWorkspaceStorage()
    lateinit var controller: platform.UIKit.UIViewController
    val videos = IosVideoLibrary { picker ->
        var presenter = controller.view.window?.rootViewController ?: controller
        while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
        presenter.presentViewController(picker, true, null)
    }
    controller = ComposeUIViewController { MontageApp("iOS", storage, videos) }
    return controller
}
