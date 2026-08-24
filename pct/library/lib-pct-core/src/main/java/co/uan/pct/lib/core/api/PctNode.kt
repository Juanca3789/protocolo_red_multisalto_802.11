package co.uan.pct.lib.core.api

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fachada del nodo PCT: ciclo init → start → close.
 * Sin UI; la app observa [phase], [topology], [debug] y [events].
 */
interface PctNode {
    /** UUID4 sin guiones (32 hex). */
    val nodeId: String

    val phase: StateFlow<NodePhase>
    val topology: StateFlow<TopologySnapshot>
    /** GO / STA / DNS-SD / candidatos (laboratorio). */
    val debug: StateFlow<PctDebugSnapshot>
    val events: SharedFlow<PctEvent>
    val neighbors: StateFlow<NeighborSnapshot>
    val routes: StateFlow<RouteSnapshot>

    fun init(context: Context, config: PctConfig = PctConfig())

    /** Bootstrap automático: scan → join mejor padre | iniciar raíz. */
    fun start()

    fun close()

    /** Envía payload usuario multisalto (canal datos L3). */
    fun sendUser(destinationNid: String, payload: ByteArray)
}
