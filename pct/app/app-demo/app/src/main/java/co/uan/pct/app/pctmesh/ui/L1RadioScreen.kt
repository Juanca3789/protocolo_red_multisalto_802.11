package co.uan.pct.app.pctmesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.uan.pct.lib.core.types.BootStep
import co.uan.pct.lib.core.types.SessionStatus

@Composable
fun L1RadioScreen(
    viewModel: RadioProbeViewModel,
    onStartClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val browsing = ui.status as? SessionStatus.Booting.Browsing
    val failed = ui.status as? SessionStatus.Failed

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

        if (browsing != null) {
            val fraction = if (browsing.windowSec <= 0) {
                0f
            } else {
                (browsing.elapsedSec.toFloat() / browsing.windowSec).coerceIn(0f, 1f)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(48.dp),
                )
                Column {
                    Text(
                        text = "${browsing.candidates} encontrados",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${browsing.elapsedSec}/${browsing.windowSec} s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            phaseRows(ui.status, ui.sawJoin).forEach { row ->
                PhaseLine(row)
            }
        }

        if (failed != null) {
            Text(
                text = failed.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

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

@Composable
private fun PhaseLine(row: PhaseRowUi) {
    val color = when (row.mark) {
        PhaseMark.DONE -> MaterialTheme.colorScheme.primary
        PhaseMark.CURRENT -> MaterialTheme.colorScheme.primary
        PhaseMark.ERROR -> MaterialTheme.colorScheme.error
        PhaseMark.SKIPPED, PhaseMark.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glyph = when (row.mark) {
        PhaseMark.DONE -> "✓"
        PhaseMark.CURRENT -> "●"
        PhaseMark.PENDING -> "○"
        PhaseMark.SKIPPED -> "—"
        PhaseMark.ERROR -> "✗"
    }
    val label = if (row.detail.isNullOrBlank()) row.title else "${row.title} · ${row.detail}"
    Text(
        text = "$glyph  $label",
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
}

private enum class PhaseMark { PENDING, CURRENT, DONE, SKIPPED, ERROR }

private data class PhaseRowUi(
    val title: String,
    val mark: PhaseMark,
    val detail: String? = null,
)

private enum class UiPhase { SEARCH, JOIN, GO, PUBLISH, ADDRESS, LISTEN, ONLINE }

private fun phaseRows(status: SessionStatus, sawJoin: Boolean): List<PhaseRowUi> {
    val current = currentPhase(status)
    val failedAt = (status as? SessionStatus.Failed)?.step?.toUiPhase()
    return UiPhase.entries.map { phase ->
        PhaseRowUi(
            title = phase.title,
            mark = markFor(phase, current, failedAt, status, sawJoin),
            detail = detailFor(phase, status),
        )
    }
}

private fun currentPhase(status: SessionStatus): UiPhase? = when (status) {
    is SessionStatus.Stopped -> null
    is SessionStatus.Booting.Browsing -> UiPhase.SEARCH
    is SessionStatus.Booting.JoiningParent -> UiPhase.JOIN
    is SessionStatus.Booting.CreatingGo -> UiPhase.GO
    is SessionStatus.Booting.Publishing -> UiPhase.PUBLISH
    is SessionStatus.Booting.WaitingGoAddress -> UiPhase.ADDRESS
    is SessionStatus.Booting.OpeningListeners -> UiPhase.LISTEN
    is SessionStatus.Online -> UiPhase.ONLINE
    is SessionStatus.Failed -> status.step.toUiPhase()
}

private fun BootStep.toUiPhase(): UiPhase = when (this) {
    BootStep.BROWSE -> UiPhase.SEARCH
    BootStep.JOIN_PARENT -> UiPhase.JOIN
    BootStep.CREATE_GO -> UiPhase.GO
    BootStep.PUBLISH -> UiPhase.PUBLISH
    BootStep.GO_ADDRESS -> UiPhase.ADDRESS
    BootStep.LISTEN -> UiPhase.LISTEN
}

private val UiPhase.title: String
    get() = when (this) {
        UiPhase.SEARCH -> "Buscar red"
        UiPhase.JOIN -> "Unirse al padre"
        UiPhase.GO -> "Abrir grupo"
        UiPhase.PUBLISH -> "Publicar"
        UiPhase.ADDRESS -> "Esperar IP"
        UiPhase.LISTEN -> "Abrir escuchas"
        UiPhase.ONLINE -> "En red"
    }

private fun markFor(
    phase: UiPhase,
    current: UiPhase?,
    failedAt: UiPhase?,
    status: SessionStatus,
    sawJoin: Boolean,
): PhaseMark {
    if (current == null) return PhaseMark.PENDING
    if (failedAt == phase) return PhaseMark.ERROR
    if (phase == UiPhase.JOIN && phase != current && shouldSkipJoin(status, sawJoin, current)) {
        return PhaseMark.SKIPPED
    }
    return when {
        phase == current && status !is SessionStatus.Online -> PhaseMark.CURRENT
        phase == current && status is SessionStatus.Online -> PhaseMark.DONE
        phase.ordinal < current.ordinal -> PhaseMark.DONE
        else -> PhaseMark.PENDING
    }
}

private fun shouldSkipJoin(status: SessionStatus, sawJoin: Boolean, current: UiPhase): Boolean {
    if (current.ordinal <= UiPhase.JOIN.ordinal) return false
    if (sawJoin) return false
    return parentSsidOf(status) == null
}

private fun parentSsidOf(status: SessionStatus): String? = when (status) {
    is SessionStatus.Booting.JoiningParent -> status.ssid
    is SessionStatus.Booting.WaitingGoAddress -> status.parentSsid
    is SessionStatus.Booting.OpeningListeners -> status.parentSsid
    is SessionStatus.Booting.Publishing -> status.parentSsid
    is SessionStatus.Online -> status.parentSsid
    else -> null
}

private fun detailFor(phase: UiPhase, status: SessionStatus): String? = when (phase) {
    UiPhase.JOIN -> parentSsidOf(status)
    UiPhase.ONLINE -> if (status is SessionStatus.Online) {
        "${status.role.name} · listen=${status.listen.name} · up=${status.upstream.name}"
    } else {
        null
    }
    else -> null
}
