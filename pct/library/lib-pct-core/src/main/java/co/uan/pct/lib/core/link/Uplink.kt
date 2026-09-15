package co.uan.pct.lib.core.link

import java.net.InetAddress
import java.net.Socket

/**
 * Lo único que L1 le entrega a L2 cuando la STA al padre está arriba: a qué dirección
 * conectar el TCP de control y cómo atar cada socket a esa red.
 *
 * La dirección es la `fe80::` del GO padre con ámbito en la interfaz STA. No puede ser
 * `192.168.49.1`: todo GO de Android es `.1`, y en cuanto este nodo levanta su propio grupo esa
 * dirección pasa a ser local suya (tabla `local` del kernel gana a la de la red) y los paquetes
 * al padre se entregan en `lo`.
 *
 * Igualdad por identidad: cada asociación STA nueva es un [Uplink] nuevo aunque repita dirección.
 */
class Uplink(
    val parentNid: String?,
    val address: InetAddress,
    val ctrlPort: Int = LinkLayer.CTRL_PORT,
    val dataPort: Int = LinkLayer.DATA_PORT,
    val bind: (Socket) -> Unit = {},
) {
    override fun toString(): String = "${address.hostAddress}:$ctrlPort"
}
