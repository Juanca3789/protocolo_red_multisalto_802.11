# 10 — Capacidades y perfiles hardware

**Versión:** 0.1.0-pre

---

## 1. Vector `capabilities` (1 byte en HELLO; 2 hex en TXT)

| Bit | Máscara | Nombre | Descripción |
|---|---|---|---|
| 0 | 0x01 | `CAP_GO_STA_CONCURRENT` | GO propio + STA legacy upstream |
| 1 | 0x02 | `CAP_DNS_SD_WHILE_STA` | DNS-SD activo con STA upstream |
| 2 | 0x04 | `CAP_CUSTOM_GO_SSID` | Acepta `setNetworkName` custom |
| 3 | 0x08 | `CAP_DUAL_STA` | Dual STA (DAG físico avanzado) |
| 4 | 0x10 | `CAP_LISTEN_WHILE_GO_STA` | `discoverServices` con GO+STA |
| 5–7 | — | Reservado | 0 |

**Valor teórico documentado pre-lab:** `0x03` (bits 0+1 asumidos hasta prueba).

---

## 2. Procedimiento de caracterización (Incremento 2)

Ejecutar en cada dispositivo de laboratorio:

| ID prueba | Procedimiento | Bit si OK |
|---|---|---|
| CAP-T01 | `createGroup()` exitoso | prereq |
| CAP-T02 | Tras STA legacy a otro GO, `createGroup` sigue activo | bit 0 |
| CAP-T03 | Tras STA, `addLocalService(_pct-ctrl)` responde | bit 1 |
| CAP-T04 | `setNetworkName("DIRECT-PCT-test")` aceptado | bit 2 |
| CAP-T05 | Tras STA, `discoverServices()` detecta seek | bit 4 |
| CAP-T06 | `isStaApConcurrencySupported()` true | info (SoftAP path) |

Registrar resultado en tabla por dispositivo:

```
| Dispositivo | API | cap hex | Clase |
```

---

## 3. Clases de dispositivo

| Clase | cap mínimo | Roles permitidos | Topología |
|---|---|---|---|
| **α** | 0x03+ | ROOT, BRIDGE, LEAF | Cadena multisalto |
| **β** | 0x01 sin bit 1 | ROOT, LEAF; BRIDGE limitado | Estrella o cadena corta |
| **γ** | 0x00 | ROOT o LEAF | Solo 2 nodos / demo reducida |

**Requisito laboratorio anteproyecto (4 smartphones):**

- Al menos **2 dispositivos clase α** para cadena de 3+ nodos.
- Al menos **1 dispositivo α** como BRIDGE central en EXP-03.

---

## 4. Perfiles de despliegue del protocolo

### Perfil P1 — Mínimo (demo académica)

- Topología física: árbol.
- Enlace: GO homogéneo + legacy STA.
- Mensajes: HELLO, JOIN_*, PING/PONG, DATA.
- Sin DAG backup routes.

### Perfil P2 — Estándar (objetivo incremento 3–4)

- P1 + TOPO_UPDATE + NODE_DOWN + reenvío 3 saltos.
- Unión tardía + reconexión.

### Perfil P3 — Extendido (opcional)

- P2 + rutas BACKUP (DAG lógico).
- Requiere clase α en ≥50% nodos.

---

## 5. Valores teóricos de dispositivos de laboratorio (placeholder)

| ID | Marca/modelo (placeholder) | API | cap teórico | Clase |
|---|---|---|---|---|
| D1 | Smartphone A | 31 | 0x03 | α |
| D2 | Smartphone B | 31 | 0x03 | α |
| D3 | Smartphone C | 33 | 0x07 | α |
| D4 | Smartphone D | 31 | 0x01 | β |

*Reemplazar tras CAP-T01..T06 en Incremento 2.*

---

## 6. Fallbacks por clase

| Situación | Clase γ | Clase β | Clase α |
|---|---|---|---|
| BRIDGE necesario | Usar ROOT estrella | BRIDGE sin DNS-SD upstream | Normal |
| Unión tardía | Solo directo a ROOT | Ventana discover manual | Automática |
| Multisalto 3+ | No soportado | Cadena si bit0 | Soportado |
