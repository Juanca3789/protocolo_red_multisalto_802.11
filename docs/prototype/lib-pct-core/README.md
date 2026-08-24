# lib-pct-core — Estado vs capas formales

**Artefacto Maven:** `co.uan.pct:core`  
**Código:** `pct/library/lib-pct-core/`  
**App demo:** `pct/app/app-demo/`

---

## Documentación de diseño (target)

| Capa | Spec |
|---|---|
| Modelo OSI / mapa | [../protocol/13-layer-model.md](../protocol/13-layer-model.md) |
| L2 enlace | [../protocol/14-link-layer.md](../protocol/14-link-layer.md) |
| L3 red | [../protocol/15-network-layer.md](../protocol/15-network-layer.md) |
| Envoltorio paquetes | [../protocol/16-packet-envelope.md](../protocol/16-packet-envelope.md) |
| Roadmap | [../protocol/17-implementation-roadmap.md](../protocol/17-implementation-roadmap.md) |

---

## Qué funciona hoy (L1)

- Bootstrap automático: scan DNS-SD → join STA → GO BRIDGE | ROOT.
- Teardown P2P al cerrar app (anti-zombi).
- UI debug: fase, subistemas, topología, log.
- Detección SoftAP `clientList` (MAC).
- HELLO UDP provisional (`HelloHub`) — **a reemplazar** por TCP control `:8765` + canal datos `:8766`.

---

## Brechas conocidas

1. **UUID hijos en UI:** no coincide con `node_id` real → NeighborRegistry + TCP control ([14-link-layer.md](../protocol/14-link-layer.md)).
2. **Un solo canal (UDP):** spec exige **dos** TCP por vecino; user no debe competir con PING/TOPO.
3. **Sin tabla de rutas:** `TopologySnapshot` es vista UI, no Forwarder.
4. **Sin multisalto de datos:** no hay `ForwardWorker` en `:8766`.

---

## Prototipo EXP-01 (origen)

Comportamiento de laboratorio documentado en [../prototype/proto-exp01-gostat/](../prototype/proto-exp01-gostat/).
