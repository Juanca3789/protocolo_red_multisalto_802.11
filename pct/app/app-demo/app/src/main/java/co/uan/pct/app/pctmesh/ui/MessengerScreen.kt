package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uan.designsystem.uikit.components.UanButton
import com.uan.designsystem.uikit.components.UanLists
import com.uan.designsystem.uikit.components.UanTextField
import com.uan.designsystem.uikit.theme.UanThemeTokens

@Composable
fun MessengerScreen(
    viewModel: MessengerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tokens = UanThemeTokens.current
    val space = tokens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = space.md, vertical = space.xs),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        CopyableUuid(label = "Este nodo (toca para copiar)", uuid = state.nodeId)

        UanTextField(
            value = state.destinationNid,
            onValueChange = viewModel::onDestinationChange,
            label = "UUID destino (32 hex)",
            placeholder = "Selecciona abajo o pega nid completo",
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.destinationOptions.isNotEmpty()) {
            Text(
                text = "Destinos conocidos (toca para usar)",
                style = tokens.typography.subtitle,
                color = tokens.colors.primary,
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(space.xxxs),
            ) {
                items(state.destinationOptions) { option ->
                    UanLists(
                        title = option.label,
                        supportingText = option.nid,
                        onClick = { viewModel.selectDestination(option.nid) },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            items(state.messages) { line ->
                ChatBubble(line)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(space.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            UanTextField(
                value = state.messageText,
                onValueChange = viewModel::onMessageChange,
                label = "Mensaje",
                placeholder = "Escribe…",
                modifier = Modifier.weight(1f),
            )
            UanButton(
                text = "Enviar",
                onClick = viewModel::sendMessage,
            )
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    val tokens = UanThemeTokens.current
    val align = if (line.isOutgoing) Alignment.End else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Text(
            text = if (line.isOutgoing) {
                "yo · ${line.timestamp}"
            } else {
                "${line.fromNid} · ${line.timestamp}"
            },
            style = tokens.typography.small,
            color = tokens.colors.muted,
            textAlign = if (line.isOutgoing) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = line.text,
            style = tokens.typography.body,
            color = tokens.colors.onSurface,
            textAlign = if (line.isOutgoing) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
