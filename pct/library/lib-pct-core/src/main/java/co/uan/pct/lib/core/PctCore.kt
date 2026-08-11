package co.uan.pct.lib.core

import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.internal.PctNodeImpl

/**
 * Punto de entrada de `lib-pct-core`.
 */
object PctCore {

    fun create(): PctNode = PctNodeImpl()

    @Deprecated("Usar PctCore.create() / PctNode", ReplaceWith("\"Hola mundo desde modulo\""))
    fun holaMundo(): String = "Hola mundo desde modulo"
}
