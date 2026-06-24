# 12 — Constantes teóricas pre-implementación

**Versión:** 0.1.0-pre

Valores **meramente teóricos** para diseño y primera implementación Android. Deben refinarse tras EXP-01..EXP-06.

---

## 1. Identidad y versión

| Constante | Valor | Tipo |
|---|---|---|
| `PCT_PROTOCOL_VERSION` | 1 | uint8 |
| `PCT_MAGIC` | `PCT1` (0x50435431) | char[4] |
| `PCT_CTRL_PORT_DEFAULT` | 8765 | uint16 |
| `PCT_CTRL_PORT_ALT` | 8766 | uint16 (fallback) |
| `NODE_ID_SIZE` | 16 | bytes (UUID) |
| `NID_SHORT_HEX_LEN` | 8 | chars |

---

## 2. Temporizadores (ms)

| Constante | Valor (ms) | Descripción |
|---|---|---|
| `T_ROOT_ELECT_MS` | 3000 | Auto-elección ROOT |
| `T_DISCOVER_MS` | 5000 | Intervalo `discoverServices` |
| `T_DISCOVER_JITTER_MS` | 500 | Jitter aleatorio ± |
| `T_PING_MS` | 5000 | Intervalo PING |
| `T_PONG_TIMEOUT_MS` | 15000 | Caída vecino |
| `T_ROUTE_STALE_MS` | 45000 | Ruta STALE |
| `T_OFFER_TTL_MS` | 10000 | Validez JOIN_OFFER |
| `T_JOIN_STA_MS` | 15000 | Timeout asociación legacy |
| `T_JOIN_MAX_MS` | 60000 | Invariante unión dinámica |
| `T_SERVICE_SWITCH_MS` | 2000 | Cambio seek→ctrl |
| `T_RECONFIG_TARGET_MS` | 15000 | Objetivo reconexión (anteproyecto) |
| `T_TCP_CONNECT_MS` | 5000 | Timeout connect TCP |
| `T_HELLO_RETRY_MS` | 2000 | Reintento HELLO |
| `T_JOIN_BACKOFF_MS` | 3000 | Backoff JOIN fallido |

---

## 3. Límites de protocolo

| Constante | Valor | Unidad |
|---|---|---|
| `HOP_LIMIT_MAX` | 7 | saltos |
| `PATH_TRACE_MAX` | 8 | nodos en DATA |
| `TOPO_TTL_DEFAULT` | 7 | saltos gossip |
| `TOPO_UPDATE_MAX_ENTRIES` | 8 | rutas/mensaje |
| `ROUTE_TABLE_MAX` | 32 | entradas/nodo |
| `MAX_CHILDREN` | 4 | hijos directos |
| `MAX_NEIGHBORS` | 8 | vecinos TCP |
| `JOIN_RETRY_MAX` | 3 | reintentos |
| `TCP_PAYLOAD_MAX` | 1400 | bytes |
| `DATA_USER_MAX` | 1024 | bytes |
| `GO_SSID_MAX` | 22 | chars |
| `GO_PSK_MAX` | 16 | chars |
| `DNS_TXT_MAX_PER_KEY` | 255 | bytes |
| `DNS_TXT_TOTAL_MAX` | 884 | bytes |

---

## 4. Epoch y versiones iniciales

| Constante | Valor inicial | Incremento |
|---|---|---|
| `EPOCH_INITIAL` | 1 | +1 reconexión mayor / caída ROOT |
| `TREE_VERSION_INITIAL` | 1 | +1 cada JOIN/CUT |
| `SESSION_EPOCH_INITIAL` | 1 | +1 solo nueva conversación (src,dst) |
| `PATH_SEQ_INITIAL` | 1 | +1 cada TOPO local |
| `PING_SEQ_INITIAL` | 0 | +1 cada PING |
| `OFFER_ID_INITIAL` | 1 | +1 cada JOIN_OFFER |

---

## 5. Score de elección (teórico)

| Peso | Valor |
|---|---|
| `W_BATTERY` | 10 |
| `W_CAP_CONCURRENT` | 50 |
| `W_RAM` | 20 (por 100 MB libre, cap 100) |
| `W_CHILDREN_PENALTY` | 15 |

**Ejemplo ROOT candidato:**

```
battery=90 → 900
cap concurrent → 50
ram=3072 MB → min(3072/100*20, 100) = 100
children=0 → 0
score = 1050 (teórico)
```

---

## 6. Subredes IP (referencia, no routing lógico)

| Interfaz | Subred típica Android P2P | Nota |
|---|---|---|
| P2P GO | 192.168.49.0/24 | GO = .1 |
| LocalOnlyHotspot | 192.168.43.0/24 | Si se usa fallback SoftAP |

**Política PCT:** no enrutar por IP entre subredes; usar `node_id` + `Forwarder`.

---

## 7. SSID / PSK teóricos de ejemplo

| Nodo | node_id (hex truncado) | go_ssid | go_psk (lab) |
|---|---|---|---|
| A (ROOT) | a3f21b7c… | DIRECT-PCT-a3f21b7c | K7mP9xQ2vL4nR8wT |
| B (BRIDGE) | b7c24d5e… | DIRECT-PCT-b7c24d5e | M2nQ8wR4xP9yL5vK |
| C (LEAF) | c1d2e3f4… | DIRECT-PCT-c1d2e3f4 | N5pR1zT7wX3mK9qL |

*PSK derivada teóricamente; en lab puede ser aleatoria por sesión.*

---

## 8. Enum reference (wire)

### role (uint8)

| Valor | Nombre |
|---|---|
| 0 | ISLAND |
| 1 | LEAF |
| 2 | BRIDGE |
| 3 | ROOT |

### RouteStatus (uint8)

| Valor | Nombre |
|---|---|
| 0 | ACTIVE |
| 1 | BACKUP |
| 2 | STALE |
| 3 | BROKEN |

### JoinRejectReason (uint8)

| Valor | Nombre |
|---|---|
| 0 | UNSPEC |
| 1 | EPOCH_MISMATCH |
| 2 | CAP_INCOMPATIBLE |
| 3 | BRIDGE_FULL |
| 4 | ALREADY_JOINED |

### JoinVia (uint8)

| Valor | Nombre |
|---|---|
| 0 | LEGACY_STA |
| 1 | DNS_TXT_DIRECT |
| 2 | RELAY |

---

## 9. Tamaños agregados de tráfico de control (teórico)

Escenario estable 3 nodos, ping cada 5 s:

| Tráfico | Frecuencia/nodo | Bytes/frame | B/nodo/s teórico |
|---|---|---|---|
| PING | 0.2 Hz (1 vecino) | 32 | 6.4 |
| PONG | 0.2 Hz | 32 | 6.4 |
| TOPO_UPDATE | 0.02 Hz (estable) | 217 max | 4.3 |
| **Total control** | — | — | **~17 B/s** |

Tráfico chat: 1 msg/s × 1222 B max ≈ 1222 B/s (carga usuario).

---

## 10. Changelog pre-implementación

| Versión | Fecha | Cambio |
|---|---|---|
| 0.1.0-pre | 2026-06 | Spec inicial Incremento 1 |
