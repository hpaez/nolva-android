package app.nolva

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nolva.ui.navigation.NolvaNavigation
import app.nolva.ui.theme.NolvaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Documents are private: no screenshots, screen recordings or
        // previews in Recents (claude/modelo-de-amenazas.md, R3).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val container = (application as NolvaApplication).container
        setContent {
            NolvaTheme {
                NolvaNavigation(container)
            }
        }
    }
}
