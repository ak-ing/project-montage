// SPDX-License-Identifier: Apache-2.0
import SwiftUI
import KotlinModules

struct MontageView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ViewControllerKt.ViewController()
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

@main
struct MontageApp: App {
    var body: some SwiftUI.Scene {
        WindowGroup { MontageView().ignoresSafeArea() }
    }
}
