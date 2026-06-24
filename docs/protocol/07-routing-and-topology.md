# 07 — Enrutamiento y topología

**Versión:** 0.1.0-pre

---

## 1. Modelo topológico

- **Físico (perfil mínimo):** árbol — un padre legacy STA por nodo.
- **Lógico (extendido):** DAG — entradas BACKUP en tabla local.
- **Identidad:** `node_id` (UUID 16 B); IP irrelevante para destino final.

---

## 2. Estructura `RoutingEntry`

| Campo | Tamaño | Descripción |
|---|---|---|
| `dest_nid` | 16 B | Destino |
| `next_hop_nid` | 16 B | Vecino TCP directo |
| `hop_count` | 1 B | Saltos lógicos |
| `path_seq` | 4 B | Secuencia anuncio origen |
| `status` | 1 B | ACTIVE / BACKUP / STALE / BROKEN |
| `last_seen_ms` | 8 B | Timestamp local |

**Tamaño registro en memoria (teórico):** 46 bytes  
**Máximo entradas:** `ROUTE_TABLE_MAX=32` → ~1472 B RAM tabla

---

## 3. Reglas de fusión (`TOPO_UPDATE`)

Al recibir entrada `(dest, hop, path_seq, origin)`:

1. **Aceptar** si `hop_count` < entrada conocida.
2. Si empate en hop: aceptar si `path_seq` mayor.
3. **Rechazar** si `origin` == `self` (anti-loop propagación).
4. Decrementar `ttl`; si `ttl>0`, reenviar a vecinos excepto originador.
5. Marcar STALE si `now - last_seen > T_ROUTE_STALE_MS`.

---

## 4. Prevención de ciclos (tres capas)

| Capa | Mecanismo |
|---|---|
| Propagación | `ttl` en TOPO_UPDATE; no reenviar si origin=self |
| Datos | `path_trace` en DATA; descartar si self in trace |
| Datos | `hop_limit` decrecible (default 7) |

---

## 5. Reenvío (`Forwarder`)

```
FUNCIÓN forward(msg DATA):
  SI msg.dst_nid == self.node_id:
    ENTREGAR a capa mensajería
  SI msg.hop_limit == 0: DESCARTAR
  SI self.node_id in msg.path_trace: DESCARTAR
  entry ← routing_table[msg.dst_nid]
  SI entry.status != ACTIVE: DESCARTAR
  msg.hop_limit -= 1
  msg.path_trace.append(self.node_id)
  ENVIAR msg por socket(entry.next_hop_nid)
```

---

## 6. Continuidad lógica

| Campo | Comportamiento |
|---|---|
| `epoch` | Incrementa en reconfiguración global (caída puente, nuevo ROOT) |
| `session_epoch` | **No cambia** al cambiar ruta; identifica conversación (src,dst) |
| `tree_version` | Incrementa en cada JOIN/CUT exitoso |

**Métrica anteproyecto:** tiempo desde `NODE_DOWN` hasta primer DATA entregado con mismo `session_epoch`.

---

## 7. Pseudo-NAT / mapa de vecinos

```
NeighborSocket {
  neighbor_nid:   UUID (16 B)
  socket_id:    int
  iface_class:  UPSTREAM | DOWNSTREAM
  local_ip:     uint32 (opcional, debug)
  last_ping_ms: uint64
}
```

Enrutamiento **nunca** usa `local_ip` del destino final; solo selecciona socket del `next_hop_nid`.

---

## 8. Eventos topológicos

| Evento | Acción |
|---|---|
| JOIN_COMMIT hijo | `tree_version++`; insertar ruta; TOPO_UPDATE flood |
| PING timeout padre | Marcar padre BROKEN; transitar ISLAND; `epoch++` local |
| NODE_DOWN | Purga rutas vía `failed_nid`; TOPO_UPDATE |
| Re-JOIN exitoso | Restaurar rutas; mantener `session_epoch` conversaciones |

---

## 9. Valores teóricos

| Constante | Valor | Unidad |
|---|---|---|
| `HOP_LIMIT_MAX` | 7 | saltos |
| `ROUTE_TABLE_MAX` | 32 | entradas |
| `TOPO_TTL_DEFAULT` | 7 | hops gossip |
| `T_ROUTE_STALE_MS` | 45000 | ms |
| `PATH_TRACE_MAX` | 8 | nodos |
