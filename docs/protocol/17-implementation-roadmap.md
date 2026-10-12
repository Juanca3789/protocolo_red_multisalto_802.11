# 17 — Hoja de ruta

**Código:** `pct/library/lib-pct-core` (`co.uan.pct:core` 2.3.0), `pct/app/app-demo`

---

## 1. Hoy

| Capa | Qué hay |
|---|---|
| L1 | `Radio`: browse 15 s sin GO → STA → GO + `_pct-ctrl` instance-v1. `GoControlEndpoint` (IPv4 + fe80). BSSID del padre. |
| L2 | `Link`: `:8765` PING/PONG + ANNOUNCE/OK. Tabla 1 salto. Upstream fe80. |
| L3 | `Data`: `:8766` HELLO + BCAST greedy + BCAST_RECV. `send`/`pop` en `MultiHopProtocol`. |
| Demo | Pestañas L1 / L2 / L3. Chat broadcast; nombres desde la tabla. |

Bugs de radio/TCP en Android: [link-android-known-issues.md](link-android-known-issues.md).

---

## 2. Orden que se siguió

**A — L1.** Flujo exp01 (browse → STA → GO → publish), endpoint GO atómico.

**B — L2.** Listen/connect `:8765` → ANNOUNCE → tabla.

**C — L3.** Listen/connect `:8766` solo con vecino en tabla → broadcast greedy.

---

## 3. Aún no

| Ítem | Dónde viviría |
|---|---|
| REACH / gossip de destinos >1 salto | `:8765`, tabla L2 |
| Unicast (`dest_uuid`) | mismo `:8766`, otro `msg_type` |
| `_pct-seek`, `JOIN_OFFER` / `JOIN_COMMIT` | L1 + control (Incremento 1) |
| Detección de bucle hijo↔hijo | L2 |
| Dual-STA, cifrado, NAT | — |

Spec de lo que corre: [14-link-layer.md](14-link-layer.md), [15-network-layer.md](15-network-layer.md), [16-packet-envelope.md](16-packet-envelope.md).
