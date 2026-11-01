package darkmatter.androiduse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import darkmatter.androiduse.app.ui.HomeScreen
import darkmatter.androiduse.app.ui.HomeViewModel
import darkmatter.androiduse.app.ui.theme.AndroidUseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidUseTheme {
                val viewModel = remember { HomeViewModel(applicationContext) }
                DisposableEffect(viewModel) {
                    onDispose { viewModel.close() }
                }
                HomeScreen(viewModel)
            }
        }
    }
}
