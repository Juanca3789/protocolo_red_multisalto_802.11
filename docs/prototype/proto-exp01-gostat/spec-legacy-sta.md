# Especificación — Compatibilidad STA legacy

**Prototipo:** `proto-exp01-gostat` v1.0  
**Enlace:** hijo → padre vía **Wi‑Fi infraestructura clásica** (modo STA) al SSID del GO P2P del padre

El “legacy” indica que el enlace upstream **no** usa la sesión Wi‑Fi Direct `connect()` entre apps, sino la red **WPA2-PSK** que el GO del padre expone como AP virtual (`DIRECT-…`).

---

## 1. Requisitos de plataforma

| Requisito | Valor |
|---|---|
| API mínima efectiva | **29** (Android 10) — `WifiNetworkSpecifier` |
| API mínima del módulo | **31** (Android 12) — `minSdk` del prototipo |
| Seguridad | **WPA2-PSK** únicamente |
| Autenticación | Passphrase en claro desde DNS-SD (perfil laboratorio) |

Si `SDK_INT < 29`, la app reporta: `STA legacy requiere API 29+`.

---

## 2. Origen de credenciales

El hijo obtiene `go_ssid` y `go_psk` del padre por DNS-SD P2P:

| Prioridad | Fuente | Fiabilidad en Android |
|---|---|---|
| 1 | **Instance name** (`instance-v1`, prefijo `p`) | Alta |
| 2 | Registro TXT compacto (`s`, `p`) | Baja (TXT remoto a menudo no llega) |

Campos usados en `PctCtrlRecord`:

- `goSsid` → `WifiNetworkSpecifier.Builder.setSsid(…)`
- `goPsk` → `WifiNetworkSpecifier.Builder.setWpa2Passphrase(…)`

**Validación antes de conectar:**

- `goSsid` no vacío
- `goPsk` no vacío  
Si falta PSK → error en UI; no se llama a `requestNetwork`.

---

## 3. API de conexión

### 3.1 Construcción del specifier

```kotlin
WifiNetworkSpecifier.Builder()
    .setSsid(record.goSsid)
    .setWpa2Passphrase(record.goPsk)
    .build()
```

### 3.2 NetworkRequest

```kotlin
NetworkRequest.Builder()
    .addTransportType(TRANSPORT_WIFI)
    .removeCapability(NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(specifier)
    .build()
```

| Opción | Motivo |
|---|---|
| `TRANSPORT_WIFI` | Asociación Wi‑Fi |
| Sin `NET_CAPABILITY_INTERNET` | El enlace es de control/local; no exigir salida a Internet |
| `setNetworkSpecifier` | SSID+PSK específicos (no red abierta) |

### 3.3 Solicitud y enlace al proceso

```kotlin
connectivityManager.requestNetwork(request, networkCallback)
// onAvailable:
connectivityManager.bindProcessToNetwork(network)
```

Solo **una** solicitud activa por acción de usuario (**Conectar STA (1×)**).

---

## 4. Experiencia de usuario (sistema Android)

`requestNetwork` con `WifiNetworkSpecifier` muestra un **diálogo del sistema** en el dispositivo que **inicia** la conexión (el hijo). El usuario debe **aceptar** unirse a la red `DIRECT-…`.

| Aspecto | Comportamiento |
|---|---|
| Quién ve el diálogo | Dispositivo hijo (Joiner / escaneo) |
| Cuántos diálogos | **Uno** por pulsación de Conectar (anti-tormenta en app) |
| Rechazo usuario | `onUnavailable` → `StaState.Error` |
| Aceptación | `onAvailable` → `StaState.Connected(ssid)` |

Los anunciantes (padres) **no** reciben 10 diálogos si hay 10 raíces en alcance: el hijo elige **un** candidato antes de conectar.

---

## 5. Estados STA (`StaState`)

| Estado | Descripción |
|---|---|
| `Idle` | Sin asociación upstream activa |
| `Connecting` | `requestNetwork` en curso |
| `Connected(ssid)` | Asociado al SSID del padre; proceso vinculado |
| `Error(message)` | Fallo (permiso, usuario, red no disponible) |

### 5.1 Desconexión

```kotlin
unregisterNetworkCallback(callback)
bindProcessToNetwork(null)
```

Limpia callback y estado a `Idle`.

---

## 6. Permisos

| Permiso | Rol |
|---|---|
| `CHANGE_NETWORK_STATE` | **Obligatorio** para `requestNetwork` (manifest + concedido en instalación) |
| `ACCESS_NETWORK_STATE` | Consulta de redes |
| `CHANGE_WIFI_STATE` | Coexistencia con P2P |

Sin `CHANGE_NETWORK_STATE` → `SecurityException` capturada; mensaje en UI sin crash.

---

## 7. Coexistencia GO propio + STA upstream

Perfil objetivo del protocolo (H1): tras conectar STA al padre, el hijo **enciende su propio GO** (`createGroup`) y anuncia `_pct-ctrl` como `BRIDGE`.

| Capa | Estado tras unión exitosa |
|---|---|
| STA | Conectado al `DIRECT-…` del padre |
| P2P GO | Grupo propio activo (SSID distinto al del padre) |
| DNS-SD | Anuncio local `_pct-ctrl` (no escaneo de padres) |

**v1.0 probado:** secuencia STA → `createGroup` → anuncio en segundo dispositivo.

**No probado en v1.0:** tercer dispositivo uniéndose al miembro; reenvío TCP.

---

## 8. Limitaciones conocidas

| Limitación | Detalle |
|---|---|
| Solo WPA2-PSK | No WPA3-SAE ni OWE en v1.0 |
| PSK en DNS-SD | Visible en instance; solo laboratorio |
| Sin validación de padre | No hay `JOIN_COMMIT` TCP; cualquier `_pct-ctrl` válido es aceptado |
| `bindProcessToNetwork` | Todo el proceso usa la red del padre mientras esté activo |
| Internet | Request sin capacidad Internet; apps externas no priorizadas en ese enlace |
| Reconexión automática | No implementada; desconexión manual |

---

## 9. Matriz de compatibilidad (expectativa)

| Función | API 31+ (minSdk app) | Notas |
|---|---|---|
| `WifiNetworkSpecifier` + WPA2 | ✅ | Flujo principal |
| Diálogo de confirmación usuario | ✅ | Comportamiento estándar Android 10+ |
| STA + GO P2P simultáneo | ✅* | *Depende del chip; probado en laboratorio EXP-01 |
| STA + descubrimiento DNS-SD | ⚠️ | Descubrimiento con GO propio apagado; tras STA, sin escaneo |

---

## 10. Secuencia normativa (dos dispositivos)

```text
B: scanForParents()
B: usuario selecciona candidato A
B: connectToParent()
     → requestNetwork(SSID_A, PSK_A)
     → usuario acepta diálogo sistema
     → StaState.Connected
B: (auto) createGroup()
B: (auto) addLocalService(_pct-ctrl, role=BRIDGE)
A: mantiene GO + anuncio ROOT (sin cambios)
```

---

## 11. Referencia de implementación

| Artefacto | Ruta |
|---|---|
| STA | `…/data/sta/LegacyStaRepository.kt` |
| Estados | `…/data/sta/StaState.kt` |
| Orquestación | `…/ui/Exp01ViewModel.kt` (`connectToParent`, `onUpstreamConnected`) |
