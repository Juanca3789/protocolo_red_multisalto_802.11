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
    var logExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = space.md, vertical = space.xs)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        Text(state.statusLine, style = tokens.typography.section, color = tokens.colors.primary)
        if (state.action.isNotBlank()) {
            Text(state.action, style = tokens.typography.body, color = tokens.colors.onSurface)
        }
        state.lastError?.let {
            Text(it, style = tokens.typography.body, color = tokens.colors.error)
        }
        CopyableUuid(label = "Este nodo (toca para copiar)", uuid = state.nodeId)

        SectionDivider()
        SectionTitle("Enlace")
        val parentId = state.parentId
        if (parentId != null) {
            CopyableUuid(label = "Padre", uuid = parentId)
        } else {
            MonoLine("Sin padre")
        }
        MonoLine("Grupo propio ${if (state.goOn) "activo" else "no"} · padre ${if (state.staOn) "asociado" else "libre"}")
        if (state.goSsid.isNotBlank()) MonoLine(state.goSsid)
        if (state.searching) MonoLine("Buscando anuncios")

        SectionDivider()
        SectionTitle("Tabla de rutas (${state.routes.size})")
        if (state.routes.isEmpty()) {
            MonoLine("Solo tú. El chat usará esta tabla cuando haya vecinos.")
        } else {
            state.routes.forEach { row ->
                CopyableUuid(
                    label = "destino · ${row.hops} salto(s) vía ${row.next.take(8)}",
                    uuid = row.dest,
                )
            }
        }
        val onlyRadio = state.radio.filter { !it.inTable }
        if (onlyRadio.isNotEmpty()) {
            SectionDivider()
            SectionTitle("Radio, aún no en tabla")
            onlyRadio.forEach { seen ->
                CopyableUuid(label = seen.ssid.ifBlank { "visto" }, uuid = seen.nid)
            }
        }

        SectionDivider()
        UanLists(
            title = "Log (${state.logs.size})",
            supportingText = if (logExpanded) "Ocultar" else "Mostrar",
            onClick = { logExpanded = !logExpanded },
        )
        if (logExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(space.xxxs),
            ) {
                state.logs.asReversed().forEach { line ->
                    Text(line, style = tokens.typography.small, color = tokens.colors.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    val space = UanThemeTokens.current.spacing
    UanDivider(modifier = Modifier.padding(vertical = space.sm))
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
