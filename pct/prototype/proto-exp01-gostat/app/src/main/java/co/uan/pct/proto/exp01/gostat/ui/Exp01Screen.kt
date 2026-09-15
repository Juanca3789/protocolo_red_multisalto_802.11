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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.uan.pct.proto.exp01.gostat.data.p2p.model.GoState
import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlCandidate
import co.uan.pct.proto.exp01.gostat.data.sta.StaState

@Composable
fun Exp01Screen(
    viewModel: Exp01ViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val diag = uiState.dnsSdDiagnostics
    val goLine = when (val go = uiState.goState) {
        GoState.Idle -> "apagado"
        GoState.Creating -> "creando…"
        is GoState.Ready -> go.ssid
        is GoState.Error -> "error: ${go.message}"
    }
    val staLine = when (val sta = uiState.staState) {
        StaState.Idle -> "apagado"
        StaState.Connecting -> "conectando…"
        is StaState.Connected -> sta.ssid
        is StaState.Error -> "error: ${sta.message}"
    }
    val verdict = when {
        diag.txtCallbacks > 0 || uiState.parentCandidates.isNotEmpty() ->
            "TXT sí  cb=${diag.txtCallbacks}  candidatos=${uiState.parentCandidates.size}"
        uiState.isDiscovering && diag.peerCount > 0 ->
            "buscando  peers=${diag.peerCount}  TXT=0"
        uiState.isDiscovering ->
            "buscando  peers=0  TXT=0"
        else ->
            "peers=${diag.peerCount}  TXT=${diag.txtCallbacks}  candidatos=${uiState.parentCandidates.size}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("GO / anuncio / búsqueda, cada uno aparte", style = MaterialTheme.typography.titleMedium)
        Text(verdict, style = MaterialTheme.typography.bodyLarge)

        ToggleRow("GO", goLine, uiState.goWanted) { viewModel.setGoEnabled(it) }
        ToggleRow("Anuncio", if (uiState.isAdvertising) "on" else "off", uiState.advertiseWanted) {
            viewModel.setAdvertisingEnabled(it)
        }
        ToggleRow("Búsqueda", if (uiState.isDiscovering) "on" else "off", uiState.discoverWanted) {
            viewModel.setDiscoveryEnabled(it)
        }
        ToggleRow("STA", staLine, uiState.staWanted) { viewModel.setStaEnabled(it) }

        if (uiState.parentCandidates.isNotEmpty()) {
            Text("Candidatos")
            uiState.parentCandidates.forEach { candidate ->
                CandidateRow(
                    candidate = candidate,
                    selected = candidate.deviceAddress == uiState.selectedParentAddress,
                    onClick = { viewModel.selectParent(candidate.deviceAddress) },
                )
            }
        }

        Text("Log", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(uiState.logs.reversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CandidateRow(
    candidate: PctCtrlCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
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
        Text(
            "${candidate.deviceName}  ${candidate.record.goSsid}  nid=${candidate.record.nid.take(8)}",
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
