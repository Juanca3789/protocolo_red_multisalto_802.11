# 04 — Modelo de enlace: GO homogéneo + STA legacy

**Versión:** 0.1.0-pre

---

## 1. Principio rector

> Todo nodo es **P2P Group Owner** de su propio grupo. Los enlaces del árbol se establecen exclusivamente mediante **asociación STA legacy** al SSID/PSK del GO padre. Queda **prohibido** `WifiP2pManager.connect()` para formar la topología.

---

## 2. Tipos de enlace

| Tipo | Dirección | Mecanismo | Identificador físico |
|---|---|---|---|
| **D** (downstream) | Padre → hijo | Hijo hace STA legacy al GO del padre | SSID + PSK del GO padre |
| **L** (local) | Propio | `createGroup()` | SSID propio estable |

No existe enlace GM P2P en el perfil mínimo.

---

## 3. SSID estable del GO

**Formato teórico (pre-lab):**

```
GO_SSID = "DIRECT-PCT-" + hex(node_id[0..3])
Ejemplo: DIRECT-PCT-a3f21b7c
Longitud máxima: 22 caracteres ASCII (22 bytes en TXT)
```

**Reglas:**

1. Deriva solo de `node_id`; no incluye `epoch`, rol ni hop.
2. Se fija en `WifiP2pConfig.Builder.setNetworkName()` (API 29+).
3. Si el fabricante rechaza el nombre custom, fallback a SSID asignado por framework; identidad autoritativa en TXT `nid`.

**PSK teórica (solo laboratorio):**

```
GO_PSK = Base64URL(SHA256(node_id || epoch_network_secret))[0..15]
Longitud: 16 caracteres ASCII (16 bytes en TXT)
epoch_network_secret: generado en ROOT al bootstrap (32 B aleatorios)
```

---

## 4. Secuencia: interconexión de dos GO

```
Tiempo   GO-A (ROOT)                    GO-B (BRIDGE/LEAF)
──────   ───────────                    ──────────────────
 T0      createGroup()                  createGroup()
 T1      addLocalService(_pct-ctrl)     addLocalService(_pct-seek o _pct-ctrl)
 T2      discoverServices()             discoverServices()
 T3      —                              obtiene SSID/PSK de A (TXT o JOIN_OFFER)
 T4      —                              requestNetwork(SSID_A, PSK_A)
 T5      acepta STA legacy B            mantiene GO propio (hipótesis H1)
 T6      TCP HELLO ← B                  TCP HELLO → A
 T7      JOIN_ACK                       JOIN_COMMIT
```

---

## 5. Escalado a N nodos y DAG

### Árbol (perfil mínimo)

Cada nodo tiene **como máximo un padre** (un STA legacy upstream activo).

### DAG lógico (perfil extendido)

- Físico: árbol.
- Plano de control: múltiples entradas en tabla de rutas (`status=BACKUP`).
- Anti-ciclos: `path_trace`, `hop_limit`, `path_seq`.

### DAG físico (fuera alcance mínimo)

Requiere dual-STA (`cap bit CAP_DUAL_STA`); no asumido en v0.1.

---

## 6. Ventajas del modelo homogéneo GO

| Escenario | Beneficio |
|---|---|
| ISLAND cerca de LEAF | LEAF ya anuncia `_pct-ctrl` / puede recibir `_pct-seek` |
| Reconexión tras caída padre | Nodo sigue GO; solo re-asocia STA legacy |
| Unión tardía | ROOT/BRIDGE no apagan DNS-SD al operar |
| Evitar bloqueo GM→GO | Nunca se entra en rol GM |

---

## 7. Mapa de interfaces por nodo BRIDGE

```
                    ┌─────────────────┐
   legacy STA       │     Nodo B      │  P2P GO propio
   ───────────────► │  (BRIDGE)       │  ◄─────────────── legacy STA hijos
   hacia GO padre    │                 │
                    └─────────────────┘
   Interfaz UP       TCP socket padre    TCP sockets hijos   Interfaz DOWN
```

**NeighborMap (teórico):**

```
upstream:   { nid: parent, socket, iface: UPSTREAM }
downstream: [ { nid: child_i, socket, iface: DOWNSTREAM }, ... ]
```

---

## 8. Prohibiciones normativas

| Acción | Estado |
|---|---|
| `WifiP2pManager.connect()` para árbol | **PROHIBIDO** |
| Cambiar SSID GO tras JOIN | **PROHIBIDO** |
| Routing multisalto por IP kernel | **PROHIBIDO** |
| UDP broadcast como canal crítico | **NO RECOMENDADO** |
