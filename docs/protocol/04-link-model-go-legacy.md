# 04 — Modelo de enlace: GO homogéneo + STA legacy

**Versión:** 0.1.0-pre

---

## 1. Principio rector

> Todo nodo es **P2P Group Owner** de su propio grupo. Los enlaces del árbol se establecen exclusivamente mediante **asociación STA legacy** al SSID/PSK del GO padre. Queda **prohibido** `WifiP2pManager.connect()` para formar la topología.

---

## 2. Tipos de enlace


| Tipo               | Dirección    | Mecanismo                            | Identificador físico    |
| ------------------ | ------------ | ------------------------------------ | ----------------------- |
| **D** (downstream) | Padre → hijo | Hijo hace STA legacy al GO del padre | SSID + PSK del GO padre |
| **L** (local)      | Propio       | `createGroup()`                      | SSID propio estable     |


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
Tiempo   GO-A (escucha)                 GO-B (pulso de búsqueda)
──────   ───────────                    ──────────────────
 T0      createGroup()                  createGroup()
 T1      addLocalService(_pct-ctrl)     addLocalService(_pct-ctrl)
 T2      NO discoverServices            discoverServices() (anuncio sigue)
 T3      responde GAS/TXT               obtiene SSID/PSK de A
 T4      —                              stopPeerDiscovery (vuelve a solo anunciar)
 T5      —                              requestNetwork(SSID_A, PSK_A)
```

Medido en Samsung (EXP-01):

- **Sin GO**, `discoverServices` basta para ver TXT.
- **Con GO**, el que busca **también tiene que anunciar**. GO + búsqueda sin anuncio no entrega TXT.
- El otro **no puede estar buscando** a la vez: si los dos están en `discoverServices`, no hay TXT. Uno pulsa; el otro solo anuncia.

---



## 5. Escalado a N nodos y DAG

