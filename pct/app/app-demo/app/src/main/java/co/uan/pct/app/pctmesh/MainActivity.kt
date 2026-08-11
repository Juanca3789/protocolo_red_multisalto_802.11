package co.uan.pct.app.pctmesh

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
import co.uan.pct.app.pctmesh.ui.MeshScreen
import co.uan.pct.app.pctmesh.ui.MeshViewModel
import co.uan.pct.lib.core.PctPermissions
import com.uan.designsystem.uikit.theme.UanTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as PctMeshApplication

    private val viewModel by viewModels<MeshViewModel> {
        MeshViewModel.Factory(app)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.startMesh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Por si se reabre tras releaseNode() sin matar el proceso
        app.acquireNode()
        permissionLauncher.launch(PctPermissions.required)
        enableEdgeToEdge()
        setContent {
            UanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MeshScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // isFinishing = usuario salió / task removida (no rotación)
        if (isFinishing) {
            app.releaseNode()
        }
        super.onDestroy()
    }
}
