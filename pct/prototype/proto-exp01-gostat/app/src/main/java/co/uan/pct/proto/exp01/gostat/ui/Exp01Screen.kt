package co.uan.pct.proto.exp01.gostat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.uan.pct.proto.exp01.gostat.data.p2p.model.DiscoveryPhase
import co.uan.pct.proto.exp01.gostat.data.p2p.model.GoState
import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlCandidate
import co.uan.pct.proto.exp01.gostat.data.sta.StaState
import co.uan.pct.proto.exp01.gostat.ui.model.Exp01UiState
import co.uan.pct.proto.exp01.gostat.ui.model.NodePhase

@Composable
fun Exp01Screen(
    viewModel: Exp01ViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PCT · EXP01 · Nodo unificado", style = MaterialTheme.typography.headlineSmall)
        Text("Fase: ${uiState.phase.name}", style = MaterialTheme.typography.titleMedium)

        StatusSection(uiState)
        DiagnosticsSection(uiState)
        ParentCandidatesSection(uiState, viewModel::selectParent)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Auto GO tras STA", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = uiState.autoActivateAfterJoin,
                onCheckedChange = viewModel::setAutoActivateAfterJoin,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Filtro amplio (diag.)", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = uiState.broadDiscovery,
                onCheckedChange = viewModel::setBroadDiscovery,
            )
        }

        ActionButtons(viewModel, uiState)

        HorizontalDivider()
        Text("Log", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(uiState.logs.reversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusSection(uiState: Exp01UiState) {
    val goText = when (val go = uiState.goState) {
        GoState.Idle -> "GO: idle"
        GoState.Creating -> "GO: creando…"
        is GoState.Ready -> "GO: ${go.ssid} (owner=${go.isGroupOwner})"
        is GoState.Error -> "GO error: ${go.message}"
    }
    Text(goText)

    if (uiState.isAdvertising) {
        Text("Anunciando _pct-ctrl", color = MaterialTheme.colorScheme.primary)
    }
    if (uiState.isDiscovering) {
        Text("Escaneando padres… (ticks=${uiState.dnsSdDiagnostics.discoveryTicks})")
    }

    val staText = when (val sta = uiState.staState) {
        StaState.Idle -> "STA upstream: ninguno"
        StaState.Connecting -> "STA upstream: conectando…"
        is StaState.Connected -> "STA upstream: ${sta.ssid}"
        is StaState.Error -> "STA error: ${sta.message}"
    }
    Text(staText)
}

@Composable
private fun ParentCandidatesSection(
    uiState: Exp01UiState,
    onSelectParent: (String) -> Unit,
) {
    if (uiState.parentCandidates.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Padres en alcance (${uiState.parentCandidates.size}) — elige uno",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Solo se envía 1 solicitud STA al pulsar Conectar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        uiState.parentCandidates.forEachIndexed { index, candidate ->
            ParentCandidateCard(
                candidate = candidate,
                isRecommended = index == 0,
                isSelected = candidate.deviceAddress == uiState.selectedParentAddress,
                onClick = { onSelectParent(candidate.deviceAddress) },
            )
        }
    }
}

@Composable
private fun ParentCandidateCard(
    candidate: PctCtrlCandidate,
    isRecommended: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (isSelected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = colors,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val prefix = when {
                isSelected -> "✓ "
                isRecommended -> "★ "
                else -> "  "
            }
            Text(
                "${prefix}${candidate.deviceName}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "nid=${candidate.record.nid.take(8)}… role=${candidate.record.role} " +
                    "hop=${candidate.record.hop}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "GO padre: ${candidate.record.goSsid}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(uiState: Exp01UiState) {
    val diag = uiState.dnsSdDiagnostics
    if (diag.phase == DiscoveryPhase.Idle && diag.hint.isBlank() && diag.peerCount == 0) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Diagnóstico", style = MaterialTheme.typography.titleSmall)
        Text(
            "Peers: ${diag.peerCount} | Candidatos: ${uiState.parentCandidates.size} | " +
                "TXT cb: ${diag.txtCallbacks}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (diag.hint.isNotBlank()) {
            Text("→ ${diag.hint}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionButtons(
    viewModel: Exp01ViewModel,
    uiState: Exp01UiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = viewModel::startAsRoot,
            enabled = uiState.staState !is StaState.Connected,
        ) {
            Text("Iniciar raíz")
        }
        Button(
            onClick = viewModel::scanForParents,
            enabled = uiState.staState !is StaState.Connected && !uiState.isDiscovering,
        ) {
            Text("Buscar padres")
        }
        Button(
            onClick = viewModel::stopDiscovery,
            enabled = uiState.isDiscovering,
        ) {
            Text("Parar scan")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = viewModel::connectToParent,
            enabled = uiState.parentCandidates.isNotEmpty() ||
                uiState.discoveredService != null,
        ) {
            Text("Conectar STA (1×)")
        }
        Button(
            onClick = viewModel::activateAsMember,
            enabled = uiState.staState is StaState.Connected &&
                uiState.goState !is GoState.Ready,
        ) {
            Text("Activar mi GO")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = viewModel::createGo) { Text("Crear GO") }
        Button(
            onClick = viewModel::stopGo,
            enabled = uiState.goState !is GoState.Idle,
        ) {
            Text("Parar GO")
        }
        Button(
            onClick = viewModel::reAdvertise,
            enabled = uiState.goState is GoState.Ready,
        ) {
            Text("Re-anunciar")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = viewModel::disconnectSta) {
            Text("Desconectar STA")
        }
        Button(
            onClick = viewModel::stopAdvertising,
            enabled = uiState.isAdvertising,
        ) {
            Text("Parar anuncio")
        }
    }
}
