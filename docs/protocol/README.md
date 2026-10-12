# PCT — Protocolo de Control Topológico

Documentación formal del **Incremento 1**: análisis y diseño de arquitectura base.

**Proyecto de grado:** Protocolo distribuido de control y reconfiguración topológica con continuidad lógica para redes multisalto sobre Wi-Fi convencional.

**Versión del spec (lo que corre):** `0.3.0` — `lib-pct-core` 2.3.0, `:8765` (ANNOUNCE + REACH) y `:8766` (BCAST + UNICAST).  
El Incremento 1 (`0.1.0-pre`, `PCT1`, `_pct-seek`, JOIN_*) queda como diseño previo; no es el wire actual.

**Autores:** Juan Carlos Clavijo Triviño, Brandon Stiven Ganzo Murcia

---

## Incremento 2 — Capas formales (draft)

| Entregable | Documento |
|---|---|
| Modelo por capas | [13-layer-model.md](13-layer-model.md) |
| L2: TCP `:8765`, ANNOUNCE, tabla 1 salto | [14-link-layer.md](14-link-layer.md) |
| L3: broadcast greedy `:8766` | [15-network-layer.md](15-network-layer.md) |
| Unicast + aviso destinos (diseño) | [18-unicast-reach.md](18-unicast-reach.md) |
| Wire control / datos | [16-packet-envelope.md](16-packet-envelope.md) |
| Roadmap prototipo → implementación | [17-implementation-roadmap.md](17-implementation-roadmap.md) |
| Bugs link en Android (LK-xx) | [link-android-known-issues.md](link-android-known-issues.md) |
| Estado lib-pct-core | [../prototype/lib-pct-core/README.md](../prototype/lib-pct-core/README.md) |

---

## Alcance del Incremento 1

| Entregable | Documento |
|---|---|
| Requisitos priorizados | [01-requirements.md](01-requirements.md) |
| Restricciones Android 12+ | [02-platform-constraints.md](02-platform-constraints.md) |
| Arquitectura concurrente | [03-architecture.md](03-architecture.md) |
| Modelo de enlace GO + legacy STA | [04-link-model-go-legacy.md](04-link-model-go-legacy.md) |
| Servicios DNS-SD (bytes) | [05-services-dns-sd.md](05-services-dns-sd.md) |
| Mensajes TCP (bytes) | [06-tcp-messages.md](06-tcp-messages.md) |
| Enrutamiento y topología | [07-routing-and-topology.md](07-routing-and-topology.md) |
| Máquinas de estado | [08-state-machines.md](08-state-machines.md) |
| Protocolo de unión simétrica | [09-join-protocol.md](09-join-protocol.md) |
| Capacidades y perfiles hardware | [10-capabilities-and-profiles.md](10-capabilities-and-profiles.md) |
| Matriz de validación experimental | [11-validation-matrix.md](11-validation-matrix.md) |
| Constantes teóricas pre-laboratorio | [12-theoretical-constants.md](12-theoretical-constants.md) |

**Implementación Android:** convención de nombres en [../development/android-app-naming.md](../development/android-app-naming.md).

---

## Decisiones de diseño consolidadas

1. **Capa de aplicación** sobre Wi-Fi Direct y STA legacy; sin root ni modificación MAC/PHY.
2. **Todo nodo arranca como P2P GO** (`createGroup()`); prohibido `WifiP2pManager.connect()` para topología.
3. **Enlaces padre-hijo** vía asociación **STA legacy** al SSID/PSK del GO padre.
4. **Identidad** por `node_id` (UUID); IPv4 / `fe80` solo como transporte hop-a-hop dentro de L1/L2.
5. **Unión actual:** browse `_pct-ctrl` (instance-v1) → STA → GO. `_pct-seek` / `JOIN_OFFER` es Incremento 1, no implementado.
6. **Tabla L2:** hop 1 por ANNOUNCE; hop > 1 por REACH (IP = next-jump).
7. **Dos TCP:** `:8765` (PING/ANNOUNCE/REACH) y `:8766` (HELLO + BCAST hop 1 + UNICAST). Las IP no salen de L2.
8. **Continuidad lógica** (`epoch`, `session_epoch`) sigue en el diseño del Incremento 1; esta build no la usa.

---

## Convenciones

| Notación | Significado |
|---|---|
| `uint8` | Entero sin signo, 1 byte, big-endian |
| `uint16` | 2 bytes, big-endian |
| `uint32` | 4 bytes, big-endian |
| `UUID` | 16 bytes (RFC 4122 binario) |
| `node_id` | UUID del dispositivo |
| Tamaño fijo | Bytes exactos en wire format |
| Tamaño máx. | Límite superior pre-implementación |

---

## Referencia rápida de servicios

| Servicio DNS-SD | Tipo | Anunciante |
|---|---|---|
| `_pct-ctrl._tcp` | Control operativo (instance-v1: credenciales) | `ROOT`, `BRIDGE` |
| `_pct-seek._tcp` | Intención de unión (Incremento 1, no anuncia esta build) | — |

**Puertos TCP por defecto (Incremento 2):**

| Canal | Puerto |
|---|---|
| Control | `8765` |
| Datos (user) | `8766` |
