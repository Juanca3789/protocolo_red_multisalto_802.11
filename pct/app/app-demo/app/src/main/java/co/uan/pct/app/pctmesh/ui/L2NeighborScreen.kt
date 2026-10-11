package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun L2NeighborScreen(
    viewModel: RadioProbeViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tabla L2",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Destino · saltos (1.er nivel)",
            style = MaterialTheme.typography.titleSmall,
        )
        if (ui.neighborRows.isEmpty()) {
            Text(text = "—", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    ui.neighborRows,
                    key = { row -> row.shortId + row.name },
                ) { row ->
                    Text(
                        text = "${row.shortId}… · ${row.name} · ${row.ipv4OnLink} · ${row.hops}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
