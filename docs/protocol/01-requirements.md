# 01 — Requisitos

**Versión:** 0.1.0-pre | **Incremento:** 1

---

## 1. Requisitos funcionales

| ID | Prioridad | Descripción | Criterio de aceptación |
|---|---|---|---|
| RF-01 | Must | Identidad única por nodo | Cada nodo genera `node_id` UUID persistente |
| RF-02 | Must | Arranque homogéneo como P2P GO | Todo nodo ejecuta `createGroup()` al inicio |
| RF-03 | Must | Prohibición de enlace P2P GM | No usar `connect()` para topología |
| RF-04 | Must | Enlace upstream legacy STA | Hijo se asocia al SSID/PSK del GO padre |
| RF-05 | Must | Anuncio DNS-SD en GO | `addLocalService()` activo en estado GO |
| RF-06 | Must | Unión simétrica | `ISLAND` anuncia `_pct-seek`; red responde `JOIN_OFFER` |
| RF-07 | Must | Handshake TCP post-asociación | `HELLO` + `JOIN_COMMIT` tras enlace Wi-Fi |
| RF-08 | Must | Tabla de rutas local | Cada nodo mantiene rutas por `node_id` |
| RF-09 | Must | Prevención de ciclos | `path_trace` + `hop_limit` + reglas de fusión |
| RF-10 | Must | Heartbeats | `PING`/`PONG` entre vecinos TCP directos |
| RF-11 | Must | Detección de fallo | Timeout → `NODE_DOWN` → purga de rutas |
| RF-12 | Must | Reconfiguración sin reinicio global | SSID GO estable; cambios solo en `epoch` lógico |
| RF-13 | Should | Multisalto ≥ 3 nodos | Cadena A←B←C con reenvío en B |
| RF-14 | Should | Unión tardía | Nodo nuevo se une sin reiniciar red existente |
| RF-15 | Should | Continuidad lógica de sesión | `session_epoch` estable ante cambio de ruta |
| RF-16 | Could | DAG lógico multipath | Tablas con rutas backup (perfil extendido) |
| RF-17 | Won't (v1) | Cifrado E2E | Fuera de alcance anteproyecto |
| RF-18 | Won't (v1) | QoS | Fuera de alcance anteproyecto |

---

## 2. Requisitos no funcionales

| ID | Descripción | Valor teórico pre-lab |
|---|---|---|
| RNF-01 | Plataforma mínima | Android 12 (API 31) |
| RNF-02 | Wi-Fi Direct | Mandatorio (CDD) |
| RNF-03 | RAM mínima | 2 GB (no Go Edition) |
| RNF-04 | Profundidad máxima de saltos | 7 (`HOP_LIMIT_MAX`) |
| RNF-05 | Latencia objetivo reconexión | ≤ 15 s (`T_RECONFIG_TARGET_MS`) |
| RNF-06 | Puerto control TCP | 8765 (configurable) |
| RNF-07 | Codificación wire | Binario big-endian (v1) |
| RNF-08 | Tamaño máximo payload TCP | 1400 bytes |
| RNF-09 | Tamaño máximo mensaje usuario | 1024 bytes |

---

## 3. Restricciones de diseño

1. Sin acceso a capa MAC ni inyección de beacons 802.11 personalizados.
2. Sin dependencia de IP global end-to-end; routing por `node_id`.
3. SSID del GO **inmutable** por `node_id` (no incluir `epoch` ni topología).
4. PSK en TXT DNS-SD solo válido en entorno de laboratorio controlado.
5. Un solo enlace legacy upstream activo en perfil mínimo (árbol físico).

---

## 4. Trazabilidad incrementos futuros

| Requisito | Incremento |
|---|---|
| RF-01 – RF-07 | 2 (Conectividad base) |
| RF-08 – RF-13 | 3 (Enrutamiento y mensajería) |
| RF-10 – RF-12, RF-15 | 4 (Estabilidad y pruebas) |
