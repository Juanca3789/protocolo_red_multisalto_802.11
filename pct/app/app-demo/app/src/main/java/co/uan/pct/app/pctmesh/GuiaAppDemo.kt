/**
 * Dos teléfonos: start() → GO + L1 + L2 :8765 + L3 send(nid) en :8766.
 *
 * Huérfanos: se unen solos (nid mayor hace STA). La tabla L2 llena el chat.
 * Un nid en radio que no está en la tabla no es destino: se investiga (otro árbol).
 * Chat: MeshSocket.send(nid), no IP.
 *
 * adb logcat -s PctMesh
 */
@Suppress("unused")
internal object GuiaAppDemo
