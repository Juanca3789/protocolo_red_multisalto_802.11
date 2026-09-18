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
| PC-08 | **Todo GO es `192.168.49.1`** y el rango DHCP no se puede cambiar. Un nodo que es GO **y** STA de otro GO tiene esa IP como local propia: la tabla `local` del kernel gana y los paquetes al padre se entregan en `lo`, no salen por `wlan0` | El TCP al padre va a su **`fe80::` con ámbito** de la interfaz STA (EUI-64 del BSSID) y el socket se ata a la red STA (`Network.bindSocket`). `192.168.49.1` queda solo como último recurso si el BSSID viene redactado |

**PC-08, medido (A24 / M23, 2.1.18):** con IPv4 el TCP hijo→padre cae en cuanto el hijo levanta su propio grupo. En la literatura de redes Wi‑Fi Direct multigrupo es el problema central (arXiv 1601.00028; STREAM, 2024): se sortea con UDP multicast (2–6 Mbps) o con un nodo relé que no sea GO. Aquí no hace falta cambiar la topología: la interfaz de grupo de Android (`p2p-wlan0-0`, `p2p-p2p0-N`) recibe una `fe80::` EUI-64 de su MAC, y esa MAC es exactamente el BSSID que ve la STA del hijo (`WifiInfo.bssid` con `FLAG_INCLUDE_LOCATION_INFO`). Las direcciones de enlace local no chocan porque llevan el ámbito de la interfaz.

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
