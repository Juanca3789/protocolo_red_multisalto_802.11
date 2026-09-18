# 17 — Hoja de ruta

**Código:** `pct/library/lib-pct-core`, `pct/app/app-demo`

---

## 1. Hoy

| Capa | Estado |
|---|---|
| L1 | GO + anuncio + loop de búsqueda + STA (`PhysicalLayer`) |
| L2 | TCP `:8765` + tabla + DFS/merge (`LinkLayer`) |
| L3 | `MeshSocket.send(nid)` por `:8766` |

---

## 2. Orden

**A — L2.** Listen/connect `:8765` → `node_id` + sentido de arista → tabla nid→siguiente (IP solo en L2) → keep-alive → `:8766` para vecinos de la tabla.

**B — L3.** `send(nid)` usando esa tabla; bytes de usuario solo por `:8766`.

Un módulo L2, no una lista de workers. Spec: [14-link-layer.md](14-link-layer.md), [15-network-layer.md](15-network-layer.md).

---

## 3. Aún no

Dual-STA, cifrado extra, NAT, quitar DNS-SD de L1.
