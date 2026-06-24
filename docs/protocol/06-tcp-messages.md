# 06 — Mensajes TCP (wire format)

**Versión:** 0.1.0-pre

Codificación: **binario big-endian**. Todos los mensajes usan el mismo encabezado.

---

## 1. Encabezado común (12 bytes)

| Offset | Tamaño | Campo | Tipo | Descripción |
|---|---|---|---|---|
| 0 | 4 | `magic` | char[4] | Literal `0x50 0x43 0x54 0x31` (`PCT1`) |
| 4 | 1 | `version` | uint8 | Versión protocolo (`1`) |
| 5 | 1 | `msg_type` | uint8 | Tipo de mensaje (§2) |
| 6 | 1 | `flags` | uint8 | Bits reservados (0 en v0.1) |
| 7 | 1 | `_reserved` | uint8 | Reservado (0) |
| 8 | 2 | `payload_len` | uint16 | Longitud payload en bytes |
| 10 | 2 | `_reserved2` | uint16 | Reservado (0) |

**Nota:** `seq` se mueve al payload por tipo donde aplique; header fijo 12 B.

**Tamaño frame total:** `12 + payload_len` bytes.

---

## 2. Catálogo `msg_type`

| Valor | Nombre | Dirección típica |
|---|---|---|
| 0x01 | HELLO | Bidireccional (primer contacto) |
| 0x02 | JOIN_OFFER | Ofertante → solicitante |
| 0x03 | JOIN_ACCEPT | Solicitante → ofertante |
| 0x04 | JOIN_REJECT | Solicitante → ofertante |
| 0x05 | JOIN_COMMIT | Hijo → padre (post STA) |
| 0x06 | TOPO_UPDATE | Vecino → vecino |
| 0x07 | PING | Vecino → vecino |
| 0x08 | PONG | Respuesta PING |
| 0x09 | NODE_DOWN | Vecino → vecino (flood limitado) |
| 0x0A | DATA | Origen → destino (directo o reenviado) |
| 0x0B | FORWARD | Alias interno (mismo payload que DATA + flag) |
| 0x0C | SEEKER_FOUND | Relay opcional hacia ROOT |

---

## 3. Payloads por tipo

### 3.1 HELLO (0x01) — 47 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `sender_nid` | UUID |
| 16 | 1 | `role` | uint8 enum |
| 17 | 16 | `parent_nid` | UUID (0x00… si ROOT) |
| 33 | 4 | `epoch` | uint32 |
| 37 | 4 | `tree_version` | uint32 |
| 41 | 1 | `hop` | uint8 |
| 42 | 1 | `capabilities` | uint8 |
| 43 | 1 | `neighbor_count` | uint8 |
| 44 | 3 | `_pad` | uint8[3] |

**`role` enum:** 0=ISLAND, 1=LEAF, 2=BRIDGE, 3=ROOT

**Frame total:** 12 + 47 = **59 bytes**

---

### 3.2 JOIN_OFFER (0x02) — 89 bytes payload (fijo)

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 4 | `offer_id` | uint32 |
| 4 | 16 | `offerer_nid` | UUID |
| 20 | 16 | `anchor_nid` | UUID (ROOT lógico) |
| 36 | 1 | `assigned_role` | uint8 |
| 37 | 1 | `assigned_hop` | uint8 |
| 38 | 4 | `epoch` | uint32 |
| 42 | 4 | `tree_version` | uint32 |
| 46 | 22 | `go_ssid` | char[22] UTF-8, zero-padded |
| 68 | 16 | `go_psk` | char[16] UTF-8, zero-padded |
| 84 | 2 | `parent_ctrl_port` | uint16 |
| 86 | 3 | `_pad` | uint8[3] |

**Frame total:** 12 + 89 = **101 bytes**

---

### 3.3 JOIN_ACCEPT (0x03) — 24 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 4 | `offer_id` | uint32 |
| 4 | 16 | `acceptor_nid` | UUID |
| 20 | 4 | `epoch_accepted` | uint32 |

**Frame total:** 12 + 24 = **36 bytes**

---

### 3.4 JOIN_REJECT (0x04) — 6 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 4 | `offer_id` | uint32 |
| 4 | 1 | `reason` | uint8 |
| 5 | 1 | `_pad` | uint8 |

**`reason`:** 0=UNSPEC, 1=EPOCH_MISMATCH, 2=CAP_INCOMPATIBLE, 3=BRIDGE_FULL, 4=ALREADY_JOINED

**Frame total:** 12 + 6 = **18 bytes**

---

### 3.5 JOIN_COMMIT (0x05) — 28 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `committer_nid` | UUID |
| 16 | 4 | `offer_id` | uint32 |
| 20 | 4 | `epoch` | uint32 |
| 24 | 1 | `via` | uint8 |
| 25 | 3 | `_pad` | uint8[3] |

**`via`:** 0=LEGACY_STA, 1=DNS_TXT_DIRECT, 2=RELAY

**Frame total:** 12 + 28 = **40 bytes**

**Respuesta JOIN_ACK (usa HELLO extendido o payload 5 B):**

| JOIN_ACK payload | 5 B |
|---|---|
| `status` uint8 (0=OK) + `epoch` uint32 |

Frame JOIN_ACK: 12 + 5 = **17 bytes**

---

### 3.6 TOPO_UPDATE (0x06) — variable

**Cabecera payload: 29 bytes**

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `origin_nid` | UUID |
| 16 | 4 | `path_seq` | uint32 |
| 20 | 4 | `epoch` | uint32 |
| 24 | 1 | `ttl` | uint8 |
| 25 | 1 | `entry_count` | uint8 |
| 26 | 3 | `_pad` | uint8[3] |

**Entrada de ruta: 22 bytes cada una**

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `dest_nid` | UUID |
| 16 | 1 | `hop_count` | uint8 |
| 17 | 1 | `status` | uint8 |
| 18 | 4 | `path_seq` | uint32 |

**`status`:** 0=ACTIVE, 1=BACKUP, 2=STALE

**Payload total:** `29 + (entry_count × 22)` bytes  
**Máximo teórico:** `entry_count_max=8` → 29 + 176 = **205 bytes**  
**Frame máximo:** 12 + 205 = **217 bytes**

---

### 3.7 PING (0x07) — 20 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `sender_nid` | UUID |
| 16 | 4 | `seq` | uint32 |

**Frame total:** 12 + 20 = **32 bytes**

---

### 3.8 PONG (0x08) — 20 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `sender_nid` | UUID |
| 16 | 4 | `seq` | uint32 |

**Frame total:** **32 bytes**

---

### 3.9 NODE_DOWN (0x09) — 25 bytes payload

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `failed_nid` | UUID |
| 16 | 16 | `reporter_nid` | UUID — too big |

Fix NODE_DOWN:
| 0 | 16 | `failed_nid` | UUID |
| 16 | 16 | `reporter_nid` | UUID |
| 32 | 4 | `timestamp` | uint32 (unix sec) |
| 36 | 1 | `ttl` | uint8 |
| 37 | 3 | `_pad` | uint8[3] |

Payload: 40 bytes → Frame: 52 bytes

---

### 3.10 DATA (0x0A) — variable

**Cabecera payload: 58 bytes + user_data**

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `msg_id` | UUID |
| 16 | 16 | `src_nid` | UUID |
| 32 | 16 | `dst_nid` | UUID |
| 48 | 4 | `session_epoch` | uint32 |
| 52 | 1 | `hop_limit` | uint8 |
| 53 | 1 | `trace_count` | uint8 |
| 54 | 2 | `data_len` | uint16 |
| 56 | 2 | `_pad` | uint8[2] |

**path_trace:** `trace_count × 16` bytes (máx 8 → 128 B)

**user_data:** `data_len` bytes (máx 1024)

**Payload máximo teórico:** 58 + 128 + 1024 = **1210 bytes**  
**Frame máximo:** 12 + 1210 = **1222 bytes** (≤ MTU app 1400 ✓)

**Payload mínimo (sin trace, sin data):** 58 bytes → Frame 70 bytes

---

### 3.11 SEEKER_FOUND (0x0C) — 37 bytes payload (opcional relay)

| Offset | Tamaño | Campo | Tipo |
|---|---|---|---|
| 0 | 16 | `seeker_nid` | UUID |
| 16 | 16 | `reporter_nid` | UUID |
| 32 | 1 | `rssi_bucket` | uint8 |
| 33 | 4 | `_pad` | uint8[4] |

**Frame total:** 12 + 37 = **49 bytes**

---

## 4. Tabla resumen de tamaños

| Mensaje | Payload (B) | Frame total (B) |
|---|---|---|
| HELLO | 47 | 59 |
| JOIN_OFFER | 89 | 101 |
| JOIN_ACCEPT | 24 | 36 |
| JOIN_REJECT | 6 | 18 |
| JOIN_COMMIT | 28 | 40 |
| JOIN_ACK | 5 | 17 |
| TOPO_UPDATE (min) | 29 | 41 |
| TOPO_UPDATE (max, 8 rutas) | 205 | 217 |
| PING | 20 | 32 |
| PONG | 20 | 32 |
| NODE_DOWN | 40 | 52 |
| DATA (min) | 58 | 70 |
| DATA (max) | 1210 | 1222 |
| SEEKER_FOUND | 37 | 49 |

---

## 5. Orden de bytes ejemplo (HELLO)

```
50 43 54 31  01 01 00 00  00 2F 00 00   ← header (payload_len=47)
[16 bytes sender_nid]
03                            ← role ROOT
[16 bytes parent_nid zeros]
00 00 00 01                   ← epoch=1
00 00 00 01                   ← tree_version=1
00                            ← hop=0
03                            ← cap bits 0+1
00                            ← neighbor_count=0
00 00 00                      ← pad
```

---

## 6. Reglas de framing TCP

1. Leer 12 bytes header; verificar `magic` y `version`.
2. Leer exactamente `payload_len` bytes adicionales.
3. Si `payload_len` > 1400 → cerrar conexión (error protocolo).
4. Mensajes pueden concatenarse en stream TCP; procesar en bucle.

---

## 7. Valores teóricos pre-laboratorio

| Parámetro | Valor |
|---|---|
| `ctrl_port` default | 8765 |
| `version` | 1 |
| `magic` | `PCT1` |
| `hop_limit` default DATA | 7 |
| `trace_count` max | 8 |
| `TOPO_UPDATE` max entries | 8 |
| `NODE_DOWN` initial ttl | 3 |
