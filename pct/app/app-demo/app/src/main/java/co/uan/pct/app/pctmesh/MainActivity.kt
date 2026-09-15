package co.uan.pct.app.pctmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import co.uan.pct.app.pctmesh.ui.MeshScreen
import co.uan.pct.app.pctmesh.ui.MeshViewModel
import co.uan.pct.app.pctmesh.ui.MessengerScreen
import co.uan.pct.app.pctmesh.ui.MessengerViewModel
import co.uan.pct.lib.core.PctPermissions
import com.uan.designsystem.uikit.components.UanAppBar
import com.uan.designsystem.uikit.theme.UanTheme

/**
 * Activity principal: pestaña **Debug** (MeshScreen) y **Chat** (MessengerScreen).
 *
 * 1. [PctMeshApplication.acquireNode] en onCreate.
 * 2. Pide permisos → [MeshViewModel.startMesh] → `PctNode.start()`.
 * 3. onDestroy (finishing): [PctMeshApplication.releaseNode].
 *
 * Ver [GuiaAppDemo] para protocolo de prueba en dos teléfonos.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as PctMeshApplication

    private val meshViewModel by viewModels<MeshViewModel> {
        MeshViewModel.Factory(app)
    }

    private val messengerViewModel by viewModels<MessengerViewModel> {
        MessengerViewModel.Factory(app)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        meshViewModel.startMesh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.acquireNode()
        permissionLauncher.launch(PctPermissions.required)
        enableEdgeToEdge()
        setContent {
            UanTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf("Red", "Chat")

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        UanAppBar(title = "PCT Mesh")
                        TabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) },
                                )
                            }
                        }
                        when (selectedTab) {
                            0 -> MeshScreen(
                                viewModel = meshViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                            1 -> MessengerScreen(
                                viewModel = messengerViewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            app.releaseNode()
        }
        super.onDestroy()
    }
}
