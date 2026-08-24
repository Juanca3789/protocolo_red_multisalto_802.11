# 17 — Hoja de ruta: prototipo → capas formales

**Versión:** 0.2.1-draft  
**Código:** `pct/library/lib-pct-core`, `pct/app/app-demo`

---

## 1. Estado actual (lib `1.1.x`)

| Capa | Implementado | Archivo / módulo |
|---|---|---|
| L1 GO | Sí | `GoRepository`, `P2pChannelHolder` |
| L1 STA | Sí | `LegacyStaRepository` |
| L1 DNS-SD | Sí | `DnsSdRepository`, `PctInstanceCodec` |
| L1 Bootstrap auto | Sí | `PctNodeImpl.bootstrap()` |
| L2 UDP HELLO | Provisional | `HelloHub`, `HelloCodec` |
| L2 TCP | No | — |
| L2 NeighborRegistry | No | Solo `TopologySnapshot` parcial |
| L3 RoutingTable | No | — |
| L3 Forwarder | No | — |
| UI debug | Sí | `MeshScreen`, `PctDebugSnapshot` |

### 1.1 Bug conocido (UI hijos)

- **Síntoma:** lista “hijos” no muestra UUID PCT aunque el padre conoce el UUID vía DNS/HELLO.
- **Causa:** UI/topología mezclan `clientList` MAC y HELLO UDP no confiable; no hay NeighborRegistry L2.
- **Fix spec:** [14-link-layer.md](14-link-layer.md) §5.1 — identidad solo desde TCP HELLO.

---

## 2. Fases de implementación (orden obligatorio)

### Fase A — Capa de enlace formal (dos canales)

**Objetivo:** TCP control + datos, UUID en UI, recuperación datos sin reboot.

| # | Tarea | Entregable |
|---|---|---|
| A1 | `ControlAcceptWorker` `:8765` + `DataAcceptWorker` `:8766` | Dos ServerSocket en GO |
| A2 | Connect control tras STA + HELLO | [14-link-layer.md](14-link-layer.md) |
| A3 | `DATA_CHANNEL_OPEN/ACK/RESET` | Negociación `:8766` vía control |
| A4 | `NeighborRegistry` (ctrl + data sockets) | API upstream/downstream |
| A5 | JOIN_COMMIT post-HELLO | Topología UUID |
| A6 | PING/PONG solo en control | Keepalive aislado |
| A7 | `DataChannelRecoveryWorker` | Reopen `:8766` sin tocar L1 |
| A8 | Deprecar UDP HELLO | Flag lab |

**Criterio:** ráfaga user no bloquea PING; caída `:8766` se recupera por `:8765`.

### Fase B — Tabla de rutas local

**Objetivo:** cada nodo construye `RoutingTable` relativa.

| # | Tarea | Entregable |
|---|---|---|
| B1 | Estructura `RoutingEntry` | [15-network-layer.md](15-network-layer.md) |
| B2 | Rutas directas (vecinos) desde NeighborRegistry | hop=0 self, hop=1 vecino |
| B3 | TOPO_UPDATE encode/decode | Gossip con TTL |
| B4 | RouteSyncWorker fusión | Reglas §4.1 doc 15 |
| B5 | Tests cadena 3 nodos | Tablas coherentes |

### Fase C — Reenvío multisalto (solo canal datos)

**Objetivo:** `type=user` atraviesa A→B→C por `:8766`.

| # | Tarea | Entregable |
|---|---|---|
| C1 | Envelope + DATA frame en `:8766` | [16-packet-envelope.md](16-packet-envelope.md) |
| C2 | `ForwardWorker` (solo `data_socket`) | Entrega vs reenvío |
| C3 | path_trace + hop_limit | Anti-ciclo |
| C4 | Test saturación user + control estable | PING no se atrasa |
| C5 | Demo app: ping E2E | Log en C |

### Fase D — Endurecimiento

| # | Tarea |
|---|---|
| D1 | NODE_DOWN + reconvergencia |
| D2 | STALE routes timer |
| D3 | Persistencia UUID (no regenerar cada init) |
| D4 | Alinear con `_pct-seek` ([09-join-protocol.md](09-join-protocol.md)) |

---

## 3. Módulos nuevos sugeridos (lib-pct-core)

```
co.uan.pct.lib.core.internal.link
  NeighborRegistry.kt
  ControlAcceptWorker.kt
  DataAcceptWorker.kt
  ControlConnectWorker.kt
  DataConnectWorker.kt
  DataChannelRecoveryWorker.kt

co.uan.pct.lib.core.internal.route
  RoutingTable.kt
  RouteSyncWorker.kt      // solo ctrl_socket :8765
  ForwardWorker.kt        // solo data_socket :8766

co.uan.pct.lib.core.internal.packet
  PctEnvelope.kt
  EnvelopeCodec.kt
```

API pública (futuro):

```kotlin
interface PctNode {
  val neighbors: StateFlow<NeighborSnapshot>
  val routes: StateFlow<RouteSnapshot>
  fun send(destinationNid: String, payload: ByteArray)
}
```

---

## 4. Qué no hacer todavía

- Dual-STA / DAG físico.
- Cifrado fuera de WPA2 del GO.
- NAT kernel / tun/tap.
- Reemplazar DNS-SD bootstrap por TCP puro (DNS sigue siendo L1).

---

## 5. Trazabilidad documentación ↔ código

| Documento | Próximo código |
|---|---|
| [14-link-layer.md](14-link-layer.md) | `internal/link/*` |
| [15-network-layer.md](15-network-layer.md) | `internal/route/*` |
| [16-packet-envelope.md](16-packet-envelope.md) | `internal/packet/*` |
| [06-tcp-messages.md](06-tcp-messages.md) | Serialización binaria |
| [13-layer-model.md](13-layer-model.md) | README arquitectura |

---

## 6. Versión spec

Tras implementar Fase A, bump spec a `0.2.0-lab`.  
Tras Fase C, `0.3.0-multihop-lab`.
