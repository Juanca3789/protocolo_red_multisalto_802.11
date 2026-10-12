# 14 — Capa 2: TCP de control y tabla de vecinos

**Versión:** 0.5.0 (lib-pct-core 2.3.0)  
**Código:** `link/Link.kt`, `link/tcp/ControlTcpSession.kt`, `link/codec/`, `link/table/`.  
**Prerequisito:** [04-link-model-go-legacy.md](04-link-model-go-legacy.md), [13-layer-model.md](13-layer-model.md).  
**Bugs de Android:** [link-android-known-issues.md](link-android-known-issues.md).

El catálogo `PCT1` / `HI` / `TAB` / `JOIN_*` de [06-tcp-messages.md](06-tcp-messages.md) es la propuesta del Incremento 1. **No es lo que sale por el cable.**

---

## 1. En una frase

L1 entrega un `GoControlEndpoint` (listo, con IPv4) y un `Network` STA + BSSID. L2 escucha `:8765` en el GO, el hijo conecta por la STA, intercambian PING/PONG y ANNOUNCE/ANNOUNCE_OK, y dejan una fila en la tabla: `UUID · NodeId · IPv4 · saltos`. Las IP no salen de L2. Con el vecino en la tabla, L3 puede abrir `:8766`.

---

## 2. Geometría

```
role != ISLAND
    GO:  bind :8765 en hostIpv4 y en fe80 de la iface GO
    STA: bindSocket(staNetwork) → connect(fe80(BSSID), 8765)
         fallback IPv4 gateway solo si no hay BSSID (log `link: upstream ipv4 fallback`)
```

**Por qué `fe80::` y no `192.168.49.1`:** [02-platform-constraints.md](02-platform-constraints.md) PC-08. Todo GO es `.1`; un BRIDGE también tiene `.1` local y el TCP IPv4 al padre cae a `lo`.

No hay API pública para atar un `ServerSocket` a un `Network`. El bind es por dirección: esa IPv4 y esa fe80 solo existen en la interfaz P2P propia.

### Sentido de la arista

Quien hizo STA conecta y es **hijo** (`UPSTREAM`). Quien acepta en su GO es **padre** (`GO_INBOUND`). No hay negociación de depth ni comparación de `node_id` en esta build. Un `UPSTREAM` y 0..N inbound. Detección de bucle hijo↔hijo: **no implementada**.

---

## 3. Mensajes (`:8765`)

Binario big-endian. Cada mensaje empieza por un magic de 4 ASCII. **No hay header `PCT1` ni campo versión.**

| Magic | Tipo | Cuerpo |
|---|---|---|
| `PCTG` | PING | `seq` int32 |
| `PCTP` | PONG | `seq` int32 |
| `PCTA` | ANNOUNCE | `uuid` 16 · `name_len` uint16 · `name` UTF-8 · `ipv4` 4 |
| `PCTO` | ANNOUNCE_OK | igual que ANNOUNCE |

`name_len` ∈ 1..512. IPv4 es la del anunciante **en esa arista** (STA del hijo, `.1` del GO del padre).

### Flujo de join

1. Hijo: `connect` → PING, espera PONG (8 s).
2. Hijo: ANNOUNCE (`localNodeId`, IPv4 STA).
3. Padre: registra al hijo con `hops=1`, responde ANNOUNCE_OK con su `nodeId` y `goHostIpv4`.
4. Hijo: registra al padre con `hops=1`.
5. Keep-alive: PING cada 5 s; si PONG no llega en 8 s, se cierra el socket y el hijo reintenta.

Timeout de lectura del socket: 15 s. Connect: 15 s. Reintento: 2 s.

Un frame que no sea de esos cuatro se descarta (el lector corta la sesión).

---

## 4. Tabla (`NeighborTable`)

Índice = `node_id.identifier` (UUID). API: `addEntry`, `removeEntryByUuid`, `clear`, `entries` como `StateFlow`.

| Campo | Qué es |
|---|---|
| `nodeId` | UUID + nombre |
| `ipv4OnLink` | IPv4 de esa arista (la que trajo ANNOUNCE / OK) |
| `hops` | ≥ 1; hoy siempre 1 |

No hay `next_hop`, `path_seq` ni entradas >1 salto. Eso es REACH, todavía no.

Vista hacia L3: lista de vecinos directos. L3 **no** usa `ipv4OnLink` para conectar (el accept de `:8766` se identifica con HELLO por UUID; casar por IPv4 rompe con fe80).

Si se cae el TCP o el STA, L2 cierra la sesión. `stop()` vacía la tabla. L2 no toca el GO.

---

## 5. Qué no es L2

| No | Dónde |
|---|---|
| `createGroup`, DNS-SD, STA, `GoControlEndpoint` | L1 |
| Broadcast de usuario, HELLO de datos | L3 (`data/`, `:8766`) |
| Elegir SoftAP / PSK | L1 |

`:8766` **no** lo abre Link. Lo abre `Data` cuando `neighbors` deja de estar vacío y `role != ISLAND`.

---

## 6. Constantes en código

| Nombre | Valor |
|---|---|
| Puerto | 8765 |
| PING intervalo | 5 s |
| Espera PONG / ANNOUNCE_OK | 8 s |
| `soTimeout` | 15 s |
| Connect | 15 s |
| Reintento upstream | 2 s |

---

## 7. Verificación

Codec: `ControlCodecTest` (round-trip PING/PONG/ANNOUNCE/OK).  
En dispositivo: logs `link: listen go :8765 bind=…`, `link: announce_ok parent=…`, `link: announce child=…`. Upstream esperado: `link: upstream fe80 dst=fe80::…`. Si sale `upstream ipv4 fallback`, el BSSID llegó redactado.
