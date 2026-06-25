# Especificación — DNS-SD (MDNS) sobre Wi‑Fi Direct

**Prototipo:** `proto-exp01-gostat` v1.0  
**Estado:** implementado y probado en laboratorio (2 dispositivos)

En Android, el descubrimiento de servicios P2P usa la API **DNS-SD de Wi‑Fi Direct** (`WifiP2pManager` + `WifiP2pDnsSdServiceInfo`). No es mDNS multicast en la LAN Ethernet/Wi‑Fi infraestructura; opera en el plano de **Wi‑Fi Direct** entre dispositivos cercanos.

---

## 1. Servicio implementado

| Campo | Valor |
|---|---|
| Tipo de registro | `_pct-ctrl._tcp` |
| Puerto lógico de control (reservado) | `8765` (`DEFAULT_CTRL_PORT`) |
| Anunciantes en v1.0 | `ROOT`, `BRIDGE` (según rol lógico al anunciar) |
| Buscadores | Nodo en fase `ISLAND` / escaneo de padres |

**No implementado en v1.0:** `_pct-seek._tcp`.

---

## 2. Nombre de instancia (canal primario de credenciales)

### 2.1 Motivación

En dispositivos Android probados, el callback **service found** (`DnsSdServiceResponseListener`) sí se dispara, pero el callback **TXT remoto** (`DnsSdTxtRecordListener`) a menudo **no llega**. Por eso las credenciales para STA legacy van en el **instance name**, no solo en TXT.

### 2.2 Formato `instance-v1` (obligatorio en anuncio v1.0)

```
instance = "p" + Base64URL( UTF-8( nid8 + "|" + go_ssid + "|" + go_psk ) )
```

| Componente | Regla |
|---|---|
| Prefijo | Carácter literal `p` |
| `nid8` | Primeros 8 caracteres hex de `node_id` (32 hex sin guiones) |
| Separador | `\|` (pipe) |
| `go_ssid` | SSID del GO P2P del anunciante (p. ej. `DIRECT-ab-Nombre`) |
| `go_psk` | Passphrase WPA2 del GO (8+ caracteres típico en Android) |
| Codificación | Base64 URL-safe, sin padding (`NO_WRAP`, `NO_PADDING`) |
| Longitud máxima | **63 caracteres** (límite etiqueta DNS-SD) |

**Ejemplo lógico** (valores ficticios):

```
raw   = "a3f21b7c|DIRECT-xy-Phone|K7mP9xQ2"
instance = "pYTNGYjIxYjdjfERJUkVDVC14eS1QaG9uZXxLN21QOXhRMg"
```

### 2.3 Decodificación en el buscador

1. Si `instance` comienza con `p` → decodificar Base64URL y partir por `|`.
2. Si hay 3 partes no vacías (`nid8`, `ssid`, `psk`) → candidato **usable** sin TXT.
3. Formato legado `pct-{nid8}` → **no** contiene credenciales; depende de TXT (no fiable en Android).

### 2.4 Seguridad (laboratorio)

El PSK viaja en claro dentro del instance visible por DNS-SD P2P. **Solo válido en entorno de laboratorio controlado.** No usar este perfil en producción.

---

## 3. Registro TXT (canal secundario / respaldo)

Se anuncia en paralelo con `WifiP2pDnsSdServiceInfo.newInstance(instance, "_pct-ctrl._tcp", map)`.

### 3.1 Perfil compacto (implementado)

| Clave | Obligatorio | Descripción |
|---|---|---|
| `n` | Sí | `node_id` completo (32 hex) |
| `s` | Sí | `go_ssid` |
| `p` | Sí | `go_psk` |
| `c` | No | Puerto TCP; default `8765` si falta |

**Tamaño estimado:** ~40–80 bytes según longitud de SSID/PSK.

### 3.2 Perfil extendido (parser compatible, no usado al anunciar en v1.0)

El parser acepta también claves largas del diseño teórico: `nid`, `go_ssid`, `go_psk`, `cp`, `role`, `hop`, `epoch`, etc. Ver [`docs/protocol/05-services-dns-sd.md`](../../protocol/05-services-dns-sd.md).

### 3.3 Normalización de TXT entrante

Si el OEM entrega un mapa anómalo (blob único, claves `key=value`), el parser normaliza antes de validar.

---

## 4. APIs Android usadas

| Operación | API |
|---|---|
| Anunciar | `WifiP2pManager.addLocalService(channel, WifiP2pDnsSdServiceInfo, …)` |
| Dejar de anunciar | `WifiP2pManager.clearLocalServices(…)` |
| Filtrar búsqueda | `WifiP2pDnsSdServiceRequest.newInstance("_pct-ctrl._tcp")` |
| Filtro amplio (diag.) | `WifiP2pDnsSdServiceRequest.newInstance()` |
| Registrar petición | `WifiP2pManager.addServiceRequest(…)` |
| Descubrir servicios | `WifiP2pManager.discoverServices(…)` |
| Descubrir peers | `WifiP2pManager.discoverPeers(…)` (paso previo obligatorio) |
| Listeners | `setDnsSdResponseListeners(channel, serviceListener, txtListener)` |

**Precondición de anuncio:** el dispositivo debe ser **Group Owner** (`createGroup` exitoso, `isGroupOwner == true`).

---

## 5. Secuencia de descubrimiento (buscador)

```
1. discoverPeers()
2. Esperar WIFI_P2P_PEERS_CHANGED (≥1 peer) o timeout T_PEER_WAIT_MS (4000 ms)
3. addServiceRequest(filtro)
4. discoverServices()
5. Por cada service found _pct-ctrl:
     a. Decodificar instance → añadir a lista de candidatos
     b. Si llega TXT → fusionar / actualizar candidato
6. T_CANDIDATE_SETTLE_MS (5000 ms) desde el primer candidato → cerrar escaneo
7. Usuario selecciona un candidato → lockDiscovery (sin más discoverServices)
8. Usuario pulsa Conectar STA → una sola requestNetwork
```

### 5.1 Temporizadores

| Constante | Valor | Uso |
|---|---|---|
| `T_PEER_WAIT_MS` | 4000 ms | Máximo espera peers antes de `discoverServices` |
| `T_CANDIDATE_SETTLE_MS` | 5000 ms | Ventana de acumulación de candidatos |
| `T_DISCOVER_MS` | 5000 ms | Reintento periódico de `discoverServices` (si escaneo activo) |
| `T_TXT_RETRY_MS` | 800 ms | Reintento TXT (secundario) |
| `MAX_TXT_RETRIES` | 8 | Máximo reintentos TXT tras service found |

### 5.2 Anti-tormenta (múltiples anunciantes)

| Regla | Comportamiento |
|---|---|
| Sin auto-conexión | Descubrir ≠ conectar STA |
| Un candidato activo | El usuario elige **un** padre en la lista |
| Una solicitud STA | Solo una llamada a `requestNetwork` por acción de usuario |
| Cierre de escaneo | Tras 5 s o al seleccionar padre, se detiene `discoverServices` |
| Exclusión propia | Candidatos con `nid8` igual al `node_id` local se ignoran |
| Ya conectado upstream | No iniciar escaneo si STA ya está `Connected` |

### 5.3 Ranking de candidatos (recomendación UI)

Orden estable:

1. Menor `hop` (en v1.0 suele ser `0` para todos)
2. Rol: `ROOT` < `BRIDGE` < `LEAF` < otros
3. Menor `discoveredAtMs` (el primero visto gana empates)

El primer elemento de la lista se marca como **recomendado** (★) en la UI.

---

## 6. Estructura de datos parseada (`PctCtrlRecord`)

Tras decodificar instance o TXT válido:

| Campo | Tipo | Origen v1.0 |
|---|---|---|
| `nid` | string 32 hex | TXT `n` o `nid8` rellenado; instance aporta 8 chars |
| `role` | string | `ROOT` / `BRIDGE` al anunciar; default `UNKNOWN` si solo instance |
| `hop` | int | Default `0` |
| `epoch` | long | Default `0` |
| `ctrlPort` | int | Default `8765` |
| `goSsid` | string | Instance o TXT |
| `goPsk` | string | Instance o TXT |
| `deviceAddress` | string | MAC Wi‑Fi Direct del anunciante |

---

## 7. Criterios de éxito / fallo (laboratorio)

| Observación | Interpretación |
|---|---|
| `Peers visibles: 0` | Sin alcance P2P o Wi‑Fi Direct apagado |
| Peers > 0, candidatos 0 | Host no anuncia, o Joiner con GO propio activo (apagar GO antes de buscar) |
| Service found, candidatos 0 | Instance no decodificable (formato antiguo o anuncio fallido) |
| Candidatos > 0 | Flujo correcto; usuario puede conectar |
| `go_psk vacío` en Host | `requestGroupInfo` no devolvió passphrase → Info GO / recrear grupo |

---

## 8. Referencia de implementación

| Artefacto | Ruta |
|---|---|
| Repositorio DNS-SD | `…/data/p2p/DnsSdRepository.kt` |
| Codec instance | `…/util/PctInstanceCodec.kt` |
| Parser TXT | `…/util/PctTxtParser.kt` |
| Selector de padre | `…/util/ParentSelector.kt` |
