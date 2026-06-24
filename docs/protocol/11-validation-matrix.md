# 11 — Matriz de validación experimental

**Versión:** 0.1.0-pre

Validación del **Incremento 1** (diseño) y trazabilidad a **Incrementos 2–4** (implementación).

---

## 1. Hipótesis principales

| ID | Hipótesis |
|---|---|
| H1 | GO propio + STA legacy upstream simultáneo en clase α |
| H2 | Dos GO interconectados sin `connect()` |
| H3 | Cadena 3 nodos con reenvío TCP |
| H4 | Unión tardía sin reinicio SSID |
| H5 | Reconexión mantiene `session_epoch` |

---

## 2. Experimentos

### EXP-01 — Interconexión dos GO (bloqueante)

**Prioridad:** CRÍTICA — gate del diseño.

| Campo | Valor |
|---|---|
| Incremento impl. | 2 |
| Dispositivos | D1 (A), D2 (B) — clase α |
| Precondición | Ambos `createGroup()` + DNS-SD |

**Pasos:**

1. A: GO + `_pct-ctrl` (TXT ~198 B).
2. B: GO + `_pct-seek` (TXT ~115 B).
3. B obtiene SSID/PSK de A → STA legacy.
4. Verificar B sigue GO + DNS-SD.
5. TCP HELLO (59 B) A↔B.
6. JOIN_COMMIT (40 B) + JOIN_ACK (17 B).

**Criterio éxito:** pasos 4–6 OK.

**Métrica:** `T_join_ms` < 15000 ms (teórico).

**Si falla:** reclasificar D2; no continuar a EXP-03.

---

### EXP-02 — DNS-SD en GO sin GM

| Campo | Valor |
|---|---|
| Incremento | 2 |
| Objetivo | Confirmar `addLocalService` solo con GO |

**Pasos:**

1. D1 `createGroup()` + `_pct-ctrl`.
2. D2 `discoverServices()` sin `connect()`.
3. D2 recibe TXT completo.

**Criterio:** TXT parseado con `nid`, `go_ssid`, `cp`.

---

### EXP-03 — Cadena tres nodos

| Campo | Valor |
|---|---|
| Incremento | 3 |
| Topología | A (ROOT) ← B (BRIDGE) ← C (LEAF) |

**Pasos:**

1. EXP-01 entre A y B.
2. C detecta `_pct-seek` o `_pct-ctrl` de B.
3. C → STA legacy B; JOIN completo.
4. C envía DATA (70–1222 B) a A; B reenvía.

**Criterio:** payload recibido en A con `dst_nid` correcto.

**Métricas teóricas:**

| Métrica | Objetivo |
|---|---|
| `T_join_chain_ms` | ≤ 30000 |
| `T_data_e2e_ms` (3 saltos) | ≤ 500 ms (lab ideal) |
| Pérdida DATA | 0 % en 100 mensajes |

---

### EXP-04 — Unión tardía

| Campo | Valor |
|---|---|
| Incremento | 3 |
| Precondición | A↔B operativos ≥ 60 s |

**Pasos:**

1. Encender C (ISLAND).
2. C anuncia `_pct-seek`.
3. B responde JOIN_OFFER.
4. Verificar SSID de A y B **sin cambio**.

**Criterio:** C unido; B STA→A intacto.

---

### EXP-05 — Caída nodo puente

| Campo | Valor |
|---|---|
| Incremento | 4 |
| Topología | A ← B ← C |

**Pasos:**

1. Apagar B.
2. C transita ISLAND; A recibe NODE_DOWN (52 B).
3. C re-JOIN a A si alcanza (o espera nueva topología).

**Métricas anteproyecto:**

| Métrica | Objetivo teórico |
|---|---|
| `T_reconfig_ms` | ≤ 15000 |
| `session_epoch` estable | Sí |

---

### EXP-06 — ISLAND cerca de LEAF

| Campo | Valor |
|---|---|
| Incremento | 3 |
| Objetivo | GO homogéneo en LEAF acelera unión |

**Pasos:**

1. A ← B ← L (LEAF).
2. C ISLAND entra en radio de L solamente.
3. L detecta `_pct-seek` de C.

**Criterio:** JOIN vía L sin reiniciar GO de L.

---

## 3. Matriz trazabilidad requisitos ↔ experimentos

| Requisito | EXP |
|---|---|
| RF-02, RF-03, RF-04 | EXP-01 |
| RF-05, RF-06 | EXP-02, EXP-04 |
| RF-08, RF-13 | EXP-03 |
| RF-10, RF-11, RF-12 | EXP-05 |
| RF-14 | EXP-04 |
| RF-15 | EXP-05 |

---

## 4. Plantilla de registro empírico

```markdown
## EXP-XX — Fecha YYYY-MM-DD
| Campo | Valor |
| Dispositivos | |
| cap medido | |
| Resultado | PASS / FAIL |
| T_join_ms | |
| Observaciones | |
```

---

## 5. Orden de ejecución recomendado

```
EXP-02 → EXP-01 → EXP-03 → EXP-04 → EXP-06 → EXP-05
```

EXP-01 es **gate**: sin PASS, revisar diseño o hardware antes de implementar multisalto.
