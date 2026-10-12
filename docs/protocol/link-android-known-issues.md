# Capa link en Android — bugs conocidos (LK-xx)

**Versión:** 0.1.0 (lib-pct-core 2.3.0)
**Contexto:** [02-platform-constraints.md](02-platform-constraints.md) (PC-08), [14-link-layer.md](14-link-layer.md).
**Código:** `pct/library/lib-pct-core/src/main/java/co/uan/pct/lib/core/radio/Radio.kt`, `link/Link.kt`, `link/network/StaParentNetwork.kt`.

Registro de fallos observados en dispositivo con el flujo L1 (GO + STA) → Link (TCP `:8765`), su causa en código y el arreglo aplicado. Cada ID se cita en los logs y en los commits.

---

## 1. Log que disparó el análisis

```
link: listen go fallo java.net.ServerSocket.getFileDescriptor []
link: listen go sin ipv4 network=265 flowHost=null
```

L1 encontró un `Network` cuya `interfaceName` coincide con `WifiP2pGroup.getInterface()`, pero `LinkProperties.linkAddresses` todavía no traía IPv4. El `Network` se publicó igual, Link intentó escuchar, falló, y nunca volvió a intentarlo.

---

## 2. Catálogo

| ID | Síntoma | Causa | Impacto | Estado |
|---|---|---|---|---|
| **LK-01** | `listen go sin ipv4 … flowHost=null` | `Radio.publishGoNetwork` emitía `goNetwork` aunque la IPv4 fuera null. `ConnectivityManager` expone el `Network` de la interfaz P2P antes de que tenga dirección. | Padre nunca escucha `:8765`. | Corregido: L1 publica un solo `GoControlEndpoint` (network + IPv4 + fe80 + iface) o `null`. Nunca una red sin dirección. |
| **LK-02** | Listen no se recupera cuando la IP aparece | `Link` marcaba `goListenBoundNetwork = goNetwork` aunque `openGoControlListen` fallara; el `combine(role, goNetwork)` no se re-disparaba. | Hijo agota `ANNOUNCE_OK_WAIT` / `PONG_WAIT` (8 s). | Corregido: el endpoint se marca como atado solo dentro de `onSuccess`; L1 re-evalúa cada ciclo. |
| **LK-03** | `ServerSocket.getFileDescriptor` no existe | Reflexión sobre API privada para `Network.bindSocket(FileDescriptor)`. No es portable entre OEM/ART. | Listen roto. | Corregido: sin reflexión; ver sección 4. |
| **LK-04** | Lógica de IPv4 duplicada en Radio y Link | Link volvía a leer `LinkProperties` del `Network` que L1 ya había inspeccionado. | Logs incoherentes, carreras. | Corregido: Link no consulta `ConnectivityManager` para el GO; solo usa el endpoint. |
| **LK-05** | Upstream usa gateway IPv4 `192.168.49.1` | `StaParentNetwork` + `Link.maintainUpstreamOverSta`. | En **BRIDGE** el hijo también es `.1`; PC-08: el TCP cae a `lo`. | Mitigado: destino principal `fe80::` (EUI-64 del BSSID del padre); IPv4 solo fallback. |
| **LK-06** | Spec 14 dice fe80, implementación hacía IPv4 | Deuda entre doc y código. | ANNOUNCE_OK intermitente a 2+ saltos. | Corregido junto con LK-05; el padre escucha también en su fe80 de la interfaz GO. |
| **LK-07** | Detección del GO cada 3 s | `GO_OBSERVE_INTERVAL_MS` fijo. | Ventana sin listen; el hijo conecta antes de que el padre escuche. | Mitigado: sondeo a 1 s mientras no haya endpoint; 3 s una vez publicado. |
| **LK-08** | `go: endpoint pendiente iface=p2p-p2p0-0 sin network` en bucle | `ConnectivityManager.allNetworks` **no incluye** la interfaz P2P en algunos OEM (observado: `p2p-p2p0-0`). Ningún `Network` que emparejar. | Sin endpoint, sin listen. | Corregido: las direcciones salen de `NetworkInterface.getByName(iface)` (kernel), que siempre existe cuando el GO está arriba. El `Network` de CM es opcional (`GoControlEndpoint.network: Network?`); el bind es por dirección y no lo necesita. |

---

## 3. Diseño resultante

**L1 entrega endpoints listos; Link solo consume.**

```
Radio.refreshGoClients()
  └─ requestGroupInfo → iface
       └─ NetworkInterface.getByName(iface)
            ├─ IPv4 (192.168.49.1)            ┐
            ├─ fe80:: de la iface GO           ├─ GoControlEndpoint  → goEndpointFlow
            ├─ Network de CM si existe (LK-08) │
            └─ si falta IPv4 → null            ┘
Link  (:8765) y Data (:8766)
  ├─ combine(role, goEndpoint)  → ServerSocket en IPv4 y en fe80 de la iface GO
  └─ combine(role, staNetwork, parentBssid) → Socket atado a STA → connect fe80(BSSID)

El accept de `:8766` **no** se casa con `ipv4OnLink` de la tabla (el hijo llega por fe80). La sesión de datos se identifica con HELLO (UUID) cuando el vecino ya está en tabla.
```

- `GoControlEndpoint(network?, iface, hostIpv4, linkLocalIpv6)` vive en `radio/`.
- El flow es `null` mientras el GO no tenga IPv4, cuando `requestGroupInfo` dice que no somos GO, y en `tearDown`.
- Link marca el endpoint como atado solo si el `bind` tuvo éxito. Si falla, el siguiente tick de L1 (1 s) vuelve a emitir y Link reintenta.

---

## 4. Escucha `:8765` atada a la red GO (evaluación LK-03)

No existe API pública para atar un `java.net.ServerSocket` a un `Network`: `Network.bindSocket` solo acepta `Socket`, `DatagramSocket` y `FileDescriptor`, y `ServerSocket` no expone su descriptor. Las opciones son:

| Opción | Viabilidad | Decisión |
|---|---|---|
| Reflexión `ServerSocket.getFileDescriptor` | Falla en los dispositivos de prueba (LK-03). | Descartada. |
| `android.system.Os.socket/bind/listen/accept` + `ParcelFileDescriptor` | Funciona y permite `Network.bindSocket(fd)`, pero obliga a reescribir la sesión sobre `InputStream`/`OutputStream` en lugar de `Socket`. | Diferida. Solo si el bind por dirección demuestra ser insuficiente. |
| `bind(InetSocketAddress(ipDeLaIfaceGO, 8765))` | La IPv4 `.1` y la `fe80::` del GO existen únicamente en la interfaz P2P propia, así que un bind por dirección equivale a escuchar solo ahí. | **Aplicada.** Dos `ServerSocket`: uno en IPv4, otro en fe80 con ámbito. |

Qué verificar en hardware: en un nodo **BRIDGE** la interfaz STA tiene `192.168.49.x` (x ≠ 1) y la interfaz GO tiene `.1`; el bind a `.1` no debe fallar con `EADDRINUSE` ni aceptar tráfico de la STA. Si algún OEM asigna `.1` también a la STA, hay que pasar a la opción `Os.*`.

---

## 5. Upstream fe80 (LK-05 / LK-06)

| Paso | Dónde |
|---|---|
| Pedir el BSSID real del GO padre | `StaAssociation`: `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` + `onCapabilitiesChanged` → `WifiInfo.bssid`. Requiere `ACCESS_FINE_LOCATION`. |
| Derivar la dirección | `fe80::` + EUI-64 del BSSID (bit U/L invertido, `ff:fe` en medio). |
| Ámbito | `NetworkInterface.getByName(LinkProperties(staNetwork).interfaceName)`. |
| Conectar | `Socket()` → `staNetwork.bindSocket` → `connect(fe80, 8765)`. |
| Fallback | Si el BSSID viene redactado (`02:00:00:00:00:00`) o no hay interfaz, gateway IPv4 con log `link: upstream ipv4 fallback`. Válido solo mientras el hijo **no** tenga GO propio. |

### Matriz ROOT vs BRIDGE

| Rol del hijo | Destino upstream | Riesgo | Esperado |
|---|---|---|---|
| LEAF (sin GO) | fe80 del padre; IPv4 funciona también | Ninguno | ANNOUNCE_OK en < 1 s |
| BRIDGE (GO propio) | fe80 del padre | Con IPv4 el TCP cae a `lo` (PC-08) | ANNOUNCE_OK solo por fe80 |
| ROOT | No hay upstream | — | Solo listen |

---

## 6. Logs de referencia

| Evento | Significado |
|---|---|
| `link: listen go :8765 bind=192.168.49.1 iface=p2p-wlan0-0` | Listen IPv4 arriba. |
| `link: listen go :8765 bind=fe80::…%p2p-wlan0-0` | Listen fe80 arriba. |
| `link: listen go fallo …` | `bind` falló; L1 reemitirá y Link reintenta. |
| `go: endpoint pendiente iface=… sin ipv4 addrs=N` | L1 ve la interfaz pero sin IPv4 todavía (LK-01). Se loguea una vez por motivo, no por tick. |
| `go: endpoint pendiente iface=… sin NetworkInterface` | El kernel aún no creó la interfaz que reporta `WifiP2pGroup`. |
| `link: upstream fe80 dst=fe80::…%wlan0` | Camino principal. |
| `link: upstream ipv4 fallback gateway=192.168.49.1` | BSSID redactado; solo seguro sin GO propio. |
| `link: announce_ok parent=…` | Join completo en el hijo. |
| `link: announce child=…` | Join completo en el padre. |
