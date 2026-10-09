package co.uan.pct.app.pctmesh

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import co.uan.pct.app.pctmesh.ui.RadioProbeScreen
import co.uan.pct.app.pctmesh.ui.RadioProbeViewModel
import co.uan.pct.lib.core.MultiHopProtocol
import com.uan.designsystem.uikit.components.UanAppBar
import com.uan.designsystem.uikit.theme.UanTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as PctMeshApplication

    private val viewModel by viewModels<RadioProbeViewModel> {
        RadioProbeViewModel.Factory(app)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.all { (_, granted) -> granted } && hasRadioPermissions()) {
            viewModel.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        UanAppBar(title = "PCT Radio")
                        RadioProbeScreen(
                            viewModel = viewModel,
                            onStartClick = ::onStartClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    private fun onStartClick() {
        val missing = missingRadioPermissions()
        if (missing.isEmpty()) {
            viewModel.start()
        } else {
            permissionLauncher.launch(missing)
        }
    }

    private fun hasRadioPermissions(): Boolean = missingRadioPermissions().isEmpty()

    private fun missingRadioPermissions(): Array<String> =
        MultiHopProtocol.requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
