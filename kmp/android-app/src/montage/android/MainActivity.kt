// SPDX-License-Identifier: Apache-2.0
package montage.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import montage.ui.MontageApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val storage = AndroidWorkspaceStorage(applicationContext)
        setContent {
            Box(Modifier.semantics { testTagsAsResourceId = true }) { MontageApp("Android", storage) }
        }
    }
}
