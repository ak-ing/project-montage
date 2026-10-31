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
    val previews = IosVideoPreview()
    val exporter = IosCompositionExporter()
    controller = ComposeUIViewController { MontageApp("iOS", storage, videos, previews, exporter) }
    return controller
}
