package app.nolva

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nolva.ui.navigation.NolvaNavigation
import app.nolva.ui.theme.NolvaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NolvaApplication).container
        setContent {
            NolvaTheme {
                NolvaNavigation(container)
            }
        }
    }
}
