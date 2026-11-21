package app.nolva

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.nolva.ui.documents.DocumentListRoute
import app.nolva.ui.documents.DocumentListViewModel
import app.nolva.ui.theme.NolvaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NolvaApplication).container
        setContent {
            NolvaTheme {
                DocumentListRoute(
                    viewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { DocumentListViewModel(container.documentRepository) }
                        },
                    ),
                )
            }
        }
    }
}
