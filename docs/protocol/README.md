# PCT — Protocolo de Control Topológico

Documentación formal del **Incremento 1**: análisis y diseño de arquitectura base.

**Proyecto de grado:** Protocolo distribuido de control y reconfiguración topológica con continuidad lógica para redes multisalto sobre Wi-Fi convencional.

**Versión del spec:** `0.2.1-draft` (dos canales TCP: control 8765 + datos 8766)

**Autores:** Juan Carlos Clavijo Triviño, Brandon Stiven Ganzo Murcia

---

## Incremento 2 — Capas formales (draft)

| Entregable | Documento |
|---|---|
| Modelo por capas (OSI ref.) | [13-layer-model.md](13-layer-model.md) |
| Capa de enlace L2 (TCP padre–hijo) | [14-link-layer.md](14-link-layer.md) |
| Capa de red L3 (tabla + reenvío) | [15-network-layer.md](15-network-layer.md) |
| Envoltorio control / user | [16-packet-envelope.md](16-packet-envelope.md) |
| Roadmap prototipo → implementación | [17-implementation-roadmap.md](17-implementation-roadmap.md) |
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
4. **Enrutamiento lógico** por `node_id` (UUID); IPv4 local solo como transporte hop-a-hop.
5. **Unión simétrica:** nodos `ISLAND` anuncian `_pct-seek`; miembros responden con `JOIN_OFFER`.
6. **Continuidad lógica** mediante `epoch` y `session_epoch` independientes del SSID.
7. **Dos canales TCP por vecino:** control `:8765` (L2/L3 control) y datos `:8766` (user/multisalto); recuperación de datos vía control sin reiniciar nodo.

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
| `_pct-seek._tcp` | Intención de unión | `ISLAND` |
| `_pct-ctrl._tcp` | Control operativo | `ROOT`, `BRIDGE`, `LEAF` (perfil homogéneo) |

**Puertos TCP por defecto (Incremento 2):**

| Canal | Puerto |
|---|---|
| Control | `8765` |
| Datos (user) | `8766` |
