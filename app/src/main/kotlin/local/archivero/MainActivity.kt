package local.archivero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import local.archivero.ui.BrowserScreen
import androidx.compose.ui.Modifier
import local.archivero.ui.ArchiveroTheme
import local.archivero.ui.PermissionGate
import local.archivero.ui.Tone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArchiveroTheme {
                Surface(Modifier.fillMaxSize(), color = Tone.Background) {
                    PermissionGate { BrowserScreen() }
                }
            }
        }
    }
}
