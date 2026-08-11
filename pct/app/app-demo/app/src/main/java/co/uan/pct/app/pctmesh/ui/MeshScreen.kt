package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.uan.pct.lib.core.api.NodePhase
import co.uan.pct.lib.core.api.TopologySnapshot
import com.uan.designsystem.uikit.components.UanAppBar
import com.uan.designsystem.uikit.theme.UanThemeTokens

@Composable
fun MeshScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = UanThemeTokens.current
    val debug = state.debug
    val topo = state.topology

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        UanAppBar(title = "PCT Mesh · debug")

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "nid ${state.nodeId.ifBlank { "…" }}",
                style = tokens.typography.small,
                color = tokens.colors.onSurface,
            )
            Text(
                text = "FASE ${state.phase.name}",
                style = tokens.typography.section,
                color = tokens.colors.primary,
            )
            Text(
                text = phaseGuide(state.phase),
                style = tokens.typography.body,
                color = tokens.colors.onSurface,
            )
            Text(
                text = "Ahora: ${debug.action}",
                style = tokens.typography.subtitle,
                color = tokens.colors.primary,
            )

            state.lastError?.let { err ->
                Text(
                    text = "ERROR: $err",
                    style = tokens.typography.body,
                    color = tokens.colors.error,
                )
            }

            HorizontalDivider()
            SectionTitle("Subistemas")
            MonoLine("GO   ${debug.goStatus}" + (debug.goSsid?.let { " · $it" } ?: ""))
            MonoLine("STA  ${debug.staStatus}" + (debug.staSsid?.let { " · $it" } ?: ""))
            MonoLine(
                "DNS  disc=${debug.dnsDiscovering} adv=${debug.dnsAdvertising} " +
                    "fase=${debug.dnsPhase}",
            )
            MonoLine(
                "DNS  peers=${debug.peerCount} srv=${debug.servicesSeen} " +
                    "pct=${debug.pctCtrlSeen} txt=${debug.txtCallbacks} " +
                    "ticks=${debug.discoveryTicks}",
            )
            if (debug.hint.isNotBlank()) {
                MonoLine("hint ${debug.hint}")
            }

            HorizontalDivider()
            SectionTitle("Topología")
            TopologyBlock(topo)

            HorizontalDivider()
            SectionTitle("Candidatos padres (${debug.candidates.size})")
            if (debug.candidates.isEmpty()) {
                MonoLine("(ninguno — en SCANNING espera settle, o no hay GO anunciando)")
            } else {
                debug.candidates.forEachIndexed { i, c ->
                    MonoLine(
                        "[$i] ${c.deviceName} nid=${c.nodeId.take(8)}… " +
                            "${c.role} hop=${c.hop}",
                    )
                    MonoLine("    GO ${c.goSsid}")
                    MonoLine("    ${c.deviceAddress}")
                }
            }

            HorizontalDivider()
            SectionTitle("Log (${state.logs.size}) — más reciente arriba")
            state.logs.asReversed().forEach { line ->
                Text(
                    text = line,
                    style = tokens.typography.small,
                    color = tokens.colors.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val tokens = UanThemeTokens.current
    Text(text, style = tokens.typography.subtitle, color = tokens.colors.primary)
}

@Composable
private fun MonoLine(text: String) {
    val tokens = UanThemeTokens.current
    Text(text, style = tokens.typography.small, color = tokens.colors.onSurface)
}

@Composable
private fun TopologyBlock(topo: TopologySnapshot?) {
    if (topo == null) {
        MonoLine("(sin snapshot)")
        return
    }
    MonoLine("self  ${topo.self.role} hop=${topo.self.hop} go=${topo.self.goSsid ?: "—"}")
    MonoLine(
        "padre " + (
            topo.parent?.let {
                "${it.nodeId.take(8)}… ${it.role} hop=${it.hop} ${it.goSsid ?: ""}"
            } ?: "ninguno"
            ),
    )
    MonoLine("hijos ${topo.children.size} · peers conocidos ${topo.knownPeers.size}")
}

private fun phaseGuide(phase: NodePhase): String = when (phase) {
    NodePhase.ISLAND ->
        "Isla: sin mesh. Tras permisos corre bootstrap, o quedó idle/cerrado."
    NodePhase.SCANNING ->
        "Escaneo: GO apagado, busca peers/servicios _pct-ctrl. Mira peers/pct/candidatos."
    NodePhase.JOINING ->
        "Unión: eligió padre y pide STA (diálogo Wi‑Fi). Luego crea GO bridge."
    NodePhase.MEMBER ->
        "Miembro: STA al padre + GO propio anunciando. Otros pueden unirse a este GO."
    NodePhase.ROOT ->
        "Raíz: sin padre. GO + anuncio. Si no había candidatos, caíste aquí."
}
