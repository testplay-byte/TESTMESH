package com.testplaybyte.loom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomToastHost
import com.testplaybyte.loom.ui.nav.LoomNavHost
import com.testplaybyte.loom.ui.theme.LoomTheme
import com.testplaybyte.loom.ui.theme.loomColors

/**
 * Single-activity host (docs/06 §1/§3): the Compose navigation graph
 * mounts here, plus the app-wide toast slot. Edge-to-edge; system bars are
 * tinted by [LoomTheme].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: LoomViewModel = viewModel(
                factory = LoomViewModel.Factory(application as LoomApp),
            )
            val settings by vm.settings.collectAsState()

            LoomTheme(mode = settings.theme.let {
                if (it == com.testplaybyte.loom.domain.model.ThemeMode.LIGHT) {
                    com.testplaybyte.loom.ui.theme.LoomThemeMode.LIGHT
                } else {
                    com.testplaybyte.loom.ui.theme.LoomThemeMode.DARK
                }
            }) {
                LoomAppShell(vm)
            }
        }
    }
}

@Composable
private fun LoomAppShell(vm: LoomViewModel) {
    val navController = rememberNavController()
    val toast by vm.toast.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(loomColors.bg),
    ) {
        LoomNavHost(navController = navController, vm = vm)
        // The toast overlays everything (z-top, docs/02 §6).
        LoomToastHost(toast = toast, bottomPadding = 96.dpToast())
    }
}

private fun Int.dpToast() = androidx.compose.ui.unit.Dp(this.toFloat())
