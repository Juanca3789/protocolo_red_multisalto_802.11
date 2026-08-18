package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.uan.pct.lib.core.api.NodePhase
import co.uan.pct.lib.core.api.TopologySnapshot
import com.uan.designsystem.uikit.components.UanAppBar
import com.uan.designsystem.uikit.components.UanDivider
import com.uan.designsystem.uikit.components.UanLists
import com.uan.designsystem.uikit.theme.UanThemeTokens

@Composable
fun MeshScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = UanThemeTokens.current
    val space = tokens.spacing
    val debug = state.debug
    val topo = state.topology
    var logExpanded by rememberSaveable { mutableStateOf(false) }
    val showCandidates = state.phase == NodePhase.SCANNING ||
        state.phase == NodePhase.JOINING ||
        debug.candidates.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = space.md, vertical = space.xs)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        UanAppBar(title = "PCT Mesh · debug")

        Text(
            text = "${state.nodeId.take(8).ifEmpty { "…" }}… · ${state.phase.name}",
            style = tokens.typography.section,
            color = tokens.colors.primary,
        )
        Text(
            text = debug.action,
            style = tokens.typography.body,
            color = tokens.colors.onSurface,
        )
        state.lastError?.let { err ->
            Text(
                text = err,
                style = tokens.typography.body,
                color = tokens.colors.error,
            )
        }

        SectionDivider()
        SectionTitle("Topología")
        TopologyBlock(topo)

        SectionDivider()
        SectionTitle("Subistemas")
        MonoLine("GO  ${debug.goStatus} · clients=${debug.goClientCount}")
        debug.goSsid?.let { MonoLine("    $it") }
        MonoLine("STA ${debug.staStatus}" + (debug.staSsid?.let { " · $it" } ?: ""))
        MonoLine(
            "DNS disc=${debug.dnsDiscovering} adv=${debug.dnsAdvertising} " +
                "peers=${debug.peerCount} pct=${debug.pctCtrlSeen}",
        )
        if (debug.hint.isNotBlank()) {
            MonoLine(debug.hint)
        }

        if (showCandidates) {
            SectionDivider()
            SectionTitle("Candidatos (${debug.candidates.size})")
            if (debug.candidates.isEmpty()) {
                MonoLine("(ninguno)")
            } else {
                debug.candidates.forEachIndexed { i, c ->
                    MonoLine("[$i] ${c.deviceName} · ${c.role} hop=${c.hop}")
                    MonoLine("    ${c.goSsid}")
                }
            }
        }

        SectionDivider()
        UanLists(
            title = "Log (${state.logs.size}/40)",
            supportingText = if (logExpanded) "Ocultar" else "Mostrar",
            onClick = { logExpanded = !logExpanded },
            trailingContent = {
                Text(
                    text = if (logExpanded) "▾" else "▸",
                    style = tokens.typography.subtitle,
                    color = tokens.colors.muted,
                )
            },
        )
        if (logExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(space.xxxs),
            ) {
                state.logs.asReversed().forEach { line ->
                    Text(
                        text = line,
                        style = tokens.typography.small,
                        color = tokens.colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    val space = UanThemeTokens.current.spacing
    UanDivider(
        modifier = Modifier.padding(vertical = space.md),
    )
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
    MonoLine("self  ${topo.self.role} hop=${topo.self.hop}")
    topo.self.goSsid?.let { MonoLine("      $it") }
    MonoLine(
        "padre " + (
            topo.parent?.let { "${it.nodeId.take(8)}… ${it.role}" } ?: "ninguno"
            ),
    )
    MonoLine("hijos ${topo.children.size}")
    topo.children.forEachIndexed { i, child ->
        MonoLine("  [$i] ${child.nodeId.take(12)}…")
    }
}
