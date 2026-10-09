package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RadioProbeScreen(
    viewModel: RadioProbeViewModel,
    onStartClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onStartClick, modifier = Modifier.weight(1f)) {
                Text("Start")
            }
            Button(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }

        Text(
            text = "Estado: ${ui.role.name}",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = "Padre (MAC)",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = ui.parentMac ?: "—",
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = "Hijos (MAC)",
            style = MaterialTheme.typography.titleSmall,
        )
        if (ui.childMacs.isEmpty()) {
            Text(text = "—", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ui.childMacs, key = { it }) { mac ->
                    Text(text = mac, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
