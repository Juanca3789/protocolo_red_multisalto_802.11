# 05 — Servicios DNS-SD (Wi-Fi Direct)

**Versión:** 0.1.0-pre

Los registros DNS-SD son pares clave=valor UTF-8. Los tamaños siguientes son **límites máximos teóricos pre-implementación**.

**Límite DNS-SD por string TXT:** 255 bytes (RFC 6763).  
**Límite práctico total TXT acumulado:** ≤ 884 bytes (implementación Android típica).

---

## 1. Catálogo de servicios

| Nombre instancia | Tipo servicio | Transporte | Puerto |
|---|---|---|---|
| `pct-{nid_short}` | `_pct-seek._tcp` | P2P DNS-SD | `ctrl_port` (anuncio) |
| `pct-{nid_short}` | `_pct-ctrl._tcp` | P2P DNS-SD | `ctrl_port` |

`nid_short` = primeros 8 hex de `node_id` → 8 chars ASCII.

**Registro Android:**

```kotlin
WifiP2pDnsSdServiceInfo.newInstance(
    "pct-a3f21b7c",           // instance name (máx ~63 chars)
    "_pct-seek._tcp",         // o "_pct-ctrl._tcp"
    recordMap                 // Map<String, String>
)
```

---

## 2. Servicio `_pct-seek._tcp`

**Anunciante:** nodos en rol lógico `ISLAND` (buscan padre).

### 2.1 Registro TXT — campos

| Clave | Tipo valor | Máx bytes valor | Oblig. | Descripción |
|---|---|---|---|---|
| `v` | decimal | 3 | Sí | Versión protocolo (`1`) |
| `nid` | hex | 32 | Sí | `node_id` UUID hex sin guiones |
| `role` | enum | 6 | Sí | Siempre `ISLAND` |
| `cap` | hex | 4 | Sí | Bitmask capacidades (16 bits → 4 hex) |
| `pref` | enum | 8 | Sí | `LEAF`, `BRIDGE`, `ANY` |
| `since` | decimal | 13 | Sí | Unix epoch ms arranque |
| `cp` | decimal | 5 | Sí | Puerto TCP control (default 8765) |

### 2.2 Tamaño teórico del registro

| Componente | Bytes |
|---|---|
| Claves + `=` + valores (suma) | ~95 |
| Overhead mapa DNS-SD Android | ~20 |
| **Total estimado** | **~115 B** |
| **Margen bajo límite 255 B/string** | OK |

### 2.3 Ejemplo teórico

```
v=1
nid=a3f21b7c4d5e6f8091121314151617
role=ISLAND
cap=0003
pref=ANY
since=1737654321000
cp=8765
```

### 2.4 Bitmask `cap` (teórico)

| Bit | Valor | Significado |
|---|---|---|
| 0 | 0x0001 | `CAP_GO_STA_CONCURRENT` — GO + STA legacy simultáneo |
| 1 | 0x0002 | `CAP_DNS_SD_WHILE_STA` — DNS-SD activo con STA upstream |
| 2 | 0x0004 | `CAP_CUSTOM_GO_SSID` — acepta SSID custom |
| 3 | 0x0008 | `CAP_DUAL_STA` — dual STA (perfil avanzado) |
| 4–15 | — | Reservado |

**Valor teórico pre-lab por defecto:** `cap=0003` (bits 0 y 1 asumidos hasta prueba).

---

## 3. Servicio `_pct-ctrl._tcp`

**Anunciante:** nodos `ROOT`, `BRIDGE`, `LEAF` con GO activo.

### 3.1 Registro TXT — campos

| Clave | Tipo | Máx B | Oblig. | Descripción |
|---|---|---|---|---|
| `v` | decimal | 3 | Sí | Versión (`1`) |
| `nid` | hex | 32 | Sí | UUID nodo |
| `role` | enum | 8 | Sí | `ROOT`, `BRIDGE`, `LEAF` |
| `parent` | hex | 32 | No | UUID padre; vacío si ROOT |
| `hop` | decimal | 2 | Sí | Saltos al ROOT (0 si ROOT) |
| `epoch` | decimal | 10 | Sí | Epoch topológico actual |
| `tv` | decimal | 10 | Sí | `tree_version` |
| `cp` | decimal | 5 | Sí | Puerto TCP control |
| `go_ssid` | string | 22 | Sí | SSID GO para legacy STA |
| `go_psk` | string | 16 | Sí | PSK GO (solo lab) |
| `children` | hex list | 64 | No | Hasta 2 `nid_short` hijos directos (16 B c/u) |
| `accepts` | decimal | 1 | Sí | `1`=acepta hijos, `0`=LEAF estricto |

### 3.2 Tamaño teórico del registro

| Componente | Bytes |
|---|---|
| Campos obligatorios | ~165 |
| `children` opcional (2 hijos) | +33 |
| **Total máximo** | **~198 B** |
| **Por clave individual** | ≤ 255 B ✓ |

### 3.3 Ejemplo teórico (BRIDGE)

```
v=1
nid=b7c24d5e6f8091121314151617181920
role=BRIDGE
parent=a3f21b7c4d5e6f8091121314151617
hop=1
epoch=1
tv=3
cp=8765
go_ssid=DIRECT-PCT-a3f21b7c
go_psk=K7mP9xQ2vL4nR8wT
children=c1d2e3f4f5a6b7c8d9e0f1a2
accepts=1
```

---

## 4. Descubrimiento y escucha

| Rol | Anuncia | Escucha |
|---|---|---|
| ISLAND | `_pct-seek` | `_pct-ctrl`, `_pct-seek` |
| ROOT | `_pct-ctrl` | `_pct-seek` |
| BRIDGE | `_pct-ctrl` | `_pct-seek` |
| LEAF (homogéneo) | `_pct-ctrl` | `_pct-seek` |

**Invariante de unión dinámica:** todo miembro con `accepts=1` ejecuta `discoverServices()` cada `T_DISCOVER_MS` (teórico: 5000 ms).

---

## 5. Transición de servicio al cambiar rol

```
ISLAND  ──(JOIN_COMMIT)──►  LEAF/BRIDGE/ROOT
  _pct-seek                    _pct-ctrl

removeLocalService(_pct-seek)
addLocalService(_pct-ctrl)
```

**Tiempo máximo de transición teórico:** `T_SERVICE_SWITCH_MS` = 2000 ms.

---

## 6. Notas de seguridad (documentación)

- `go_psk` en TXT es **solo laboratorio**; producción requeriría intercambio post-TCP con E2E.
- Ley 1581: no persistir payloads ajenos; PSK de sesión efímera por `epoch`.
