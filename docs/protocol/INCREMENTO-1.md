# Incremento 1 — Entregables de diseño

**Proyecto:** PCT — Protocolo de Control Topológico  
**Versión spec:** 0.1.0-pre  
**Estado:** Diseño formal completado (pre-implementación Android)

---

## 1. Objetivo del incremento

Definir la arquitectura lógica, restricciones de plataforma, servicios DNS-SD, mensajes TCP con tamaños en bytes, máquinas de estado y plan de validación experimental.

---

## 2. Documentos producidos

| # | Archivo | Contenido principal |
|---|---|---|
| 0 | [README.md](README.md) | Índice y decisiones consolidadas |
| 1 | [01-requirements.md](01-requirements.md) | RF/RNF trazables |
| 2 | [02-platform-constraints.md](02-platform-constraints.md) | Android 12+, APIs, HAL |
| 3 | [03-architecture.md](03-architecture.md) | Módulos, hilos, planos |
| 4 | [04-link-model-go-legacy.md](04-link-model-go-legacy.md) | GO homogéneo + STA legacy |
| 5 | [05-services-dns-sd.md](05-services-dns-sd.md) | `_pct-seek`, `_pct-ctrl`, bytes TXT |
| 6 | [06-tcp-messages.md](06-tcp-messages.md) | Wire format binario, frames |
| 7 | [07-routing-and-topology.md](07-routing-and-topology.md) | Tablas, anti-ciclos, forward |
| 8 | [08-state-machines.md](08-state-machines.md) | Radio, rol, unión |
| 9 | [09-join-protocol.md](09-join-protocol.md) | Unión simétrica, flujos |
| 10 | [10-capabilities-and-profiles.md](10-capabilities-and-profiles.md) | Vector cap, clases α/β/γ |
| 11 | [11-validation-matrix.md](11-validation-matrix.md) | EXP-01..06 |
| 12 | [12-theoretical-constants.md](12-theoretical-constants.md) | Timers, límites, enums |

---

## 3. Resumen del protocolo v0.1

### Modelo de enlace

- Todo nodo: `createGroup()` → P2P GO.
- Topología: STA legacy al SSID/PSK del padre (prohibido `connect()`).
- SSID estable: `DIRECT-PCT-{nid_short}`.

### Servicios DNS-SD

| Servicio | TXT teórico | Anunciante |
|---|---|---|
| `_pct-seek._tcp` | ~115 B | ISLAND |
| `_pct-ctrl._tcp` | ~198 B max | ROOT, BRIDGE, LEAF |

### Mensajes TCP principales

| Mensaje | Frame (B) |
|---|---|
| HELLO | 59 |
| JOIN_OFFER | 101 |
| JOIN_COMMIT | 40 |
| TOPO_UPDATE (max) | 217 |
| DATA (max) | 1222 |
| PING / PONG | 32 |

### Puerto control default

`8765`

---

## 4. Pendientes UML (diagramas)

- [ ] Diagrama de clases → `03-architecture.md` §2
- [ ] Secuencia 2 GO → `09-join-protocol.md` §6
- [ ] Secuencia 3 nodos → `11-validation-matrix.md` EXP-03
- [ ] Estados → `08-state-machines.md` §5

Herramientas sugeridas: PlantUML, draw.io, o Mermaid exportado desde los `.md`.

---

## 5. Próximo paso (Incremento 2)

1. Proyecto Android (`minSdk 31`).
2. Ejecutar CAP-T01..T06 → actualizar `10-capabilities-and-profiles.md` §5.
3. **EXP-01** (gate): dos GO + STA legacy.
4. Implementar `P2pGoManager`, `P2pDnsSdManager`, `TcpControlPlane`.

---

## 6. Referencia al anteproyecto

| Objetivo específico anteproyecto | Documento |
|---|---|
| Diseño protocolo distribuido | 04, 05, 06, 07 |
| Estructura comunicación lógica | 07, 09 |
| Diagramas UML / secuencia | 03, 08, 11 (pendiente export) |
| Restricciones Android 12+ | 02, 10 |
