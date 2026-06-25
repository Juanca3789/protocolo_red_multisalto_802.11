# Especificación — P2P Wi‑Fi Direct (GO)

**Prototipo:** `proto-exp01-gostat` v1.0  
**Plataforma:** Android 12+ (`minSdk 31`, `targetSdk 36`)

---

## 1. Modelo de enlace P2P

Cada nodo mantiene un **grupo P2P propio** actuando como **Group Owner (GO)** mediante `createGroup()`. No se usa `WifiP2pManager.connect()` para formar la topología multisalto.

| Concepto | Especificación v1.0 |
|---|---|
| Rol Wi‑Fi Direct local | Siempre GO del propio grupo |
| Formación de grupo | `createGroup(channel, ActionListener)` |
| Disolución | `removeGroup(channel, ActionListener)` |
| Información del grupo | `requestGroupInfo(channel) { WifiP2pGroup }` |
| Conexión P2P entre apps | **Prohibida** para topología (`connect()` no usado) |
| Enlace al padre | STA legacy al SSID/PSK del GO padre (ver [spec-legacy-sta.md](spec-legacy-sta.md)) |

---

## 2. Datos del grupo GO

Obtenidos de `WifiP2pGroup` tras `createGroup` + `requestGroupInfo`:

| Campo app | Fuente Android | Uso |
|---|---|---|
| `ssid` | `group.networkName` | Anuncio DNS-SD + identificación |
| `psk` | `group.passphrase` | Anuncio DNS-SD + STA del hijo |
| `isGroupOwner` | `group.isGroupOwner` | Debe ser `true` para `addLocalService` |

### 2.1 Passphrase vacía

En algunos dispositivos `passphrase` llega vacío en `requestGroupInfo`. Sin PSK no se puede:

- Anunciar (`advertiseCtrl` rechaza el anuncio)
- Conectar STA como hijo

**Recuperación:** pulsar **Info GO**; si sigue vacío → **Parar GO** → **Crear GO** de nuevo.

### 2.2 Formato típico de SSID

SSID generado por el framework, prefijo `DIRECT-` (p. ej. `DIRECT-ab-NombreDispositivo`). Longitud máxima 802.11: 32 octetos.

---

## 3. Canal y eventos P2P

### 3.1 Inicialización

```text
WifiP2pManager.initialize(app, mainLooper, null)
```

Un único `Channel` compartido por GO, DNS-SD y listeners (`P2pChannelHolder`).

### 3.2 BroadcastReceiver — acciones registradas

| Intent action | Uso en v1.0 |
|---|---|
| `WIFI_P2P_STATE_CHANGED_ACTION` | Registrado (sin lógica de negocio aún) |
| `WIFI_P2P_PEERS_CHANGED_ACTION` | Contar peers; disparar `discoverServices` si hay peers |
| `WIFI_P2P_CONNECTION_CHANGED_ACTION` | Tras `createGroup`, refrescar `requestGroupInfo` |
| `WIFI_P2P_THIS_DEVICE_CHANGED_ACTION` | Registrado |
| `WIFI_P2P_DISCOVERY_CHANGED_ACTION` | Log diagnóstico (STARTED/STOPPED) |

Registro del receiver: `RECEIVER_NOT_EXPORTED` en API 33+.

---

## 4. Fases del nodo (UI / lógica)

| Fase | GO local | STA upstream | DNS-SD anuncio | DNS-SD escaneo |
|---|---|---|---|---|
| `ISLAND` | Opcional apagado | No | No | Opcional (buscar padres) |
| `SCANNING` | **Apagado** | No | No | Sí |
| `JOINING` | Opcional | Conectando | No | Detenido |
| `MEMBER` | Encendido | Conectado | `_pct-ctrl` BRIDGE | No |
| `ROOT` | Encendido | No | `_pct-ctrl` ROOT | No |

### 4.1 Flujo raíz (dispositivo A)

1. `createGroup()`
2. `requestGroupInfo()` → SSID + PSK
3. `addLocalService(_pct-ctrl)` con rol `ROOT`

### 4.2 Flujo miembro (dispositivo B)

1. `removeGroup()` si había GO (para escanear)
2. Descubrimiento DNS-SD (ver spec MDNS)
3. STA al padre (una solicitud)
4. `createGroup()` (GO propio)
5. `addLocalService(_pct-ctrl)` con rol `BRIDGE`
6. Opción **Auto GO tras STA** (default ON): pasos 4–5 automáticos al conectar STA

---

## 5. Restricción crítica: dos GO y DNS-SD

**Hallazgo de laboratorio (v1.0):**

| Escenario | Descubrimiento `_pct-ctrl` |
|---|---|
| Buscador **sin** GO local + anunciante con GO | ✅ Funciona (peers + service found + instance) |
| Buscador **con** GO + anunciante con GO | ❌ Peers visibles, servicio/TXT no fiable |

**Regla operativa:** antes de **Buscar padres**, el prototipo apaga el GO local automáticamente.

---

## 6. Códigos de error P2P (`ActionListener.onFailure`)

| Código | Constante | Significado |
|---|---|---|
| 0 | `ERROR` | Error interno |
| 1 | `P2P_UNSUPPORTED` | Wi‑Fi Direct no soportado |
| 2 | `BUSY` | Framework ocupado (reintentar) |
| 3 | `NO_SERVICE_REQUESTS` | Sin peticiones de servicio registradas |

Implementación: `P2pFailureReasons.describe(reason)` en logs.

---

## 7. Permisos Android (manifest)

| Permiso | Necesario para |
|---|---|
| `ACCESS_WIFI_STATE` | Estado Wi‑Fi / P2P |
| `CHANGE_WIFI_STATE` | `createGroup`, discovery |
| `ACCESS_NETWORK_STATE` | Redes |
| `CHANGE_NETWORK_STATE` | `requestNetwork` (STA) |
| `NEARBY_WIFI_DEVICES` | P2P en API 33+ (`neverForLocation`) |
| `ACCESS_FINE_LOCATION` | P2P en API ≤ 32 (`maxSdkVersion 32`) |
| `INTERNET` | Reservado (TCP futuro) |

Permisos de ejecución solicitados al arranque: `PermissionsHelper.required`.

---

## 8. Prohibiciones explícitas (v1.0)

| Acción | Estado |
|---|---|
| `WifiP2pManager.connect()` | No implementado |
| Rol Group Client como topología | No usado |
| Modificar SSID del GO con `epoch` | No implementado (SSID estable del framework) |
| Persistir grupo P2P entre reinicios | No |

---

## 9. Referencia de implementación

| Artefacto | Ruta |
|---|---|
| GO | `…/data/p2p/GoRepository.kt` |
| Canal / receiver | `…/data/p2p/P2pChannelHolder.kt` |
| Errores P2P | `…/data/p2p/P2pFailureReasons.kt` |
| Orquestación UI | `…/ui/Exp01ViewModel.kt` |
