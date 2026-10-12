# 15 — Capa 3: broadcast greedy (`data/`)

**Versión:** 0.4.0 (lib-pct-core 2.3.0)  
**Código:** `data/Data.kt`, `data/tcp/DataTcpSession.kt`, `data/codec/`, `data/SeenCache.kt`.  
**Prerequisito:** [14-link-layer.md](14-link-layer.md). Wire: [16-packet-envelope.md](16-packet-envelope.md).

El reenvío unicast por tabla (`MeshSocket.send(destino)` + `hop_limit` + `path_trace`) es diseño del Incremento 1 / [07-routing-and-topology.md](07-routing-and-topology.md). **No corre.** Hoy L3 no mira para quién es el mensaje.

---

## 1. Reparto

L2 tiene la tabla de vecinos a 1 salto. L3 no guarda IPs.

API hacia la app (`MultiHopProtocol`):

| Método | Qué hace |
|---|---|
| `send(destination, payload)` | En v1 **ignora** `destination`. Encola FIFO y emite un BCAST. |
| `pop()` | `(origen: NodeId, payload)`. FIFO. El timestamp va en el frame, no ordena la cola. |

El nombre del origen para la UI sale de la tabla L2 (caché en la demo). A más de un salto el paquete no lleva nombre: se muestra el UUID corto.

---

## 2. Cuándo se abre `:8766`

Misma geometría que L2, otro puerto:

- **GO:** listen en el `GoControlEndpoint` (IPv4 + fe80) en cuanto `role != ISLAND` y hay endpoint. El `accept` TCP ocurre siempre; la sesión **no habla** hasta un HELLO cuyo UUID ya está en la tabla (gracia 2 s por si ANNOUNCE y HELLO llegan juntos).
- **Hijo:** `connect` por la STA a `fe80` del padre **después** de que la tabla tenga al menos un vecino (ANNOUNCE_OK hecho). Manda HELLO, espera HELLO del padre (8 s).

No se casa el accept por IPv4 remota: el hijo llega por fe80 y `ipv4OnLink` es `192.168.49.x`. El HELLO de 16 bytes es la identidad.

Si el padre no está escuchando aún, el hijo reintenta cada 2 s. Si `:8766` se cae y `:8765` sigue, se reabre solo el canal de datos.

---

## 3. Relay (broadcast greedy)

Responsabilidad **por salto**, no extremo a extremo. En A → B → C, A solo entrega a B; B entrega a C. No hay ACK multisalto.

Al recibir un BCAST:

1. Decodificar. Si falla → cerrar sesión.
2. Clave dedup `(origin_uuid, msg_id)` en `SeenCache` (LRU 512). Si ya se vio → drop, sin re-entregar.
3. `BCAST_RECV` al vecino de ingreso (misma sesión). TCP ya reintenta; el ACK es para no dudar y poder reaccionar después.
4. `incoming` ← `(origin, payload)` → `pop()`.
5. El **mismo frame** a todas las sesiones identificadas **excepto** el ingreso (padre + hijos).

Origen local (`send`): nuevo `msg_id`, `origin = local`, `ts_ms = currentTimeMillis()`, entrega local (sin ACK a uno mismo) + fan-out.

No hay `dest_uuid`, ni UUID “ALL”, ni TTL. El recorte es el árbol + el dedup. Un árbol A—B—C con un envío desde A: A local, B relay a C, C no tiene a quién más mandar.

---

## 4. Ejemplo

A (ROOT) ← B (BRIDGE) ← C (hoja). C escribe en L3:

| Nodo | Qué hace |
|---|---|
| C | BCAST local + fan-out al padre (única sesión) |
| B | dedup, ACK a C, `pop()`, fan-out a A (no a C) |
| A | dedup, ACK a B, `pop()`, no hay más vecinos |

Dos hijos de A: el mensaje de uno llega al otro porque A lo repele hacia todos menos el ingreso.

---

## 5. Qué queda fuera (siguiente)

- Unicast / `dest_uuid` / REACH en `:8765`.
- `hop_limit` y `path_trace`.
- Mensaje de control `DATA_CHANNEL_OPEN` (el canal se abre por tabla + HELLO).
