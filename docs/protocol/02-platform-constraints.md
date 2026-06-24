# 02 — Restricciones de plataforma Android 12+

**Versión:** 0.1.0-pre

---

## 1. Restricciones observadas y documentadas

| ID | Restricción | Implicación para PCT |
|---|---|---|
| PC-01 | No hay beacons 802.11 de aplicación | Descubrimiento vía DNS-SD P2P + escaneo SSID |
| PC-02 | `connect()` → rol GM bloquea `createGroup()` | Prohibir `connect()` para topología |
| PC-03 | STA upstream puede apagar anuncio P2P local | Separar `_pct-seek` (ISLAND) de `_pct-ctrl` (GO operativo) |
| PC-04 | STA + SoftAP no universal | Downstream vía P2P GO propio, no SoftAP en bridge |
| PC-05 | GO P2P ≈ Soft AP en 192.168.49.0/24 | Routing lógico por `node_id`, no por IP global |
| PC-06 | Multigrupo P2P nativo no soportado | Un GO propio + un STA legacy upstream máximo (perfil mínimo) |
| PC-07 | Android 13+ requiere `NEARBY_WIFI_DEVICES` | Declarar en manifest con `neverForLocation` si aplica |

---

## 2. Combinaciones de interfaces (Wi-Fi HAL)

Patrones típicos en dispositivos retail:

```
Combo A:  1 × STA  +  1 × P2P     ← crítico para BRIDGE
Combo B:  1 × STA  +  1 × AP      ← alternativa si downstream SoftAP
Combo C:  1 × AP bridged (DBS)    ← hotspot dual band
```

**Hipótesis H1 (validación Incremento 2):**

> Un dispositivo puede mantener P2P GO propio y, simultáneamente, asociación STA legacy al GO de un padre.

---

## 3. Permisos Android (teórico pre-implementación)

```xml
<!-- Android 13+ -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />

<!-- Android 12 y anteriores en runtime -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />

<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 4. APIs utilizadas por subsistema

| Subsistema | API Android |
|---|---|
| Creación GO | `WifiP2pManager.createGroup()` |
| Info grupo | `WifiP2pManager.requestGroupInfo()` |
| DNS-SD anuncio | `WifiP2pManager.addLocalService()` |
| DNS-SD descubrimiento | `WifiP2pManager.discoverServices()` |
| Peers | `WifiP2pManager.discoverPeers()` |
| STA legacy upstream | `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()` |
| TCP control | `java.net.ServerSocket` / `Socket` |
| Capacidades | `WifiManager.isStaApConcurrencySupported()` (+ pruebas empíricas) |

---

## 5. Lo que el protocolo NO asume

- Concurrencia STA + SoftAP en todos los dispositivos.
- Descubrimiento UDP broadcast como canal primario.
- IP routing del kernel para multisalto.
- Dual-STA para DAG físico (perfil avanzado opcional).
