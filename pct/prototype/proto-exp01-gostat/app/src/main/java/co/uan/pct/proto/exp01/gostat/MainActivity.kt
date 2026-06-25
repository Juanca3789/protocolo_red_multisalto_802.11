package co.uan.pct.proto.exp01.gostat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import co.uan.pct.proto.exp01.gostat.ui.Exp01Screen
import co.uan.pct.proto.exp01.gostat.ui.Exp01ViewModelFactory
import co.uan.pct.proto.exp01.gostat.ui.theme.PCTEXP01GOSTATheme
import co.uan.pct.proto.exp01.gostat.util.PermissionsHelper

class MainActivity : ComponentActivity() {

    private val app get() = application as PctExp01Application

    private val viewModel by viewModels<co.uan.pct.proto.exp01.gostat.ui.Exp01ViewModel> {
        Exp01ViewModelFactory(app)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* permisos concedidos o denegados; repos fallarán con callback si faltan */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(PermissionsHelper.required)
        enableEdgeToEdge()
        setContent {
            PCTEXP01GOSTATheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Exp01Screen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
