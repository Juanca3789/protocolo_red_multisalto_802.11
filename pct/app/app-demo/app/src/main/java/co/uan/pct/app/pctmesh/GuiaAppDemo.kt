/**
 * Prueba con dos teléfonos (A y B):
 *
 * 1. Abrir la app solo en A. Busca 10 s, no ve a nadie, crea su grupo y anuncia.
 *    Log esperado: `nadie a la vista; me vuelvo raíz y anuncio` → `anuncio raíz ... ssid=DIRECT-..`.
 * 2. Cuando A ya anuncia, abrir la app en B. Busca 10 s, lee el TXT de A y hace STA.
 *    Log esperado en B: `vi anuncio ssid=...; me asocio` → `asociado al padre` →
 *    `L2 asociado al padre → TCP fe80::...` → `L2 arista padre ↔ ...` → `L2 datos abiertos con ...`.
 *    En A: `L2 TCP in ...` → `L2 arista hijo ↔ ...`.
 * 3. Pestaña Chat: elegir el otro nid en la tabla y enviar. El mensaje llega por `:8766`.
 *
 * Después B crea su propio grupo y anuncia (para un tercer teléfono). Cada raíz pulsa 5 s
 * al azar dentro de cada minuto por si hay árboles que no se vieron al arrancar.
 *
 * Cerrar la app tumba búsqueda, anuncio, STA y grupo: si algo queda residual, el arranque
 * siguiente miente. Reinstalar sin cerrar antes deja un grupo zombi.
 *
 * adb logcat -s PctMesh
 */
@Suppress("unused")
internal object GuiaAppDemo
