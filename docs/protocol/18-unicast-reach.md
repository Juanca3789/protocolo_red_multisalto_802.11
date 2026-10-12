# 18 — Unicast y aviso de destinos (REACH)

**Versión:** 0.2.0 (lib-pct-core 2.3.0)  
**Estado:** implementado. Sin lógica de baja cuando se cae un vecino (se piensa después).  
**Capa:** aviso por **`:8765`** (`PCTR`). Unicast de usuario en **`:8766`** (`UNICAST` / `UNICAST_RECV`). Broadcast greedy intacto (solo hop 1).

El nombre REACH no cuadra del todo (no es “alcanzar un destino” al estilo IP): es “alguien nuevo en mi árbol, avisa a padre e hijos”. Sirve mientras no haya otro. El cuerpo es UUID + nombre + saltos del reportero; **no** la IPv4 del destino.

---

## 0. Grafo de laboratorio (referencia)

En Android **todo GO es `192.168.49.1`** (PC-08). Aquí el `IP_GO` se inventa distinto solo para leer el árbol. Las IPv4 que sí importan en la tabla son las de **STA en el GO del padre** (y el `.1` que el padre pone en ANNOUNCE_OK).

| Nodo | UUID | IP_GO (ficticia) | IP_STA (en el GO del padre) |
|---|---|---|---|
| A | `00:01` | 192.168.49.1 | — (ROOT) |
| B | `00:02` | 192.168.50.1 | 192.168.49.2 |
| C | `00:03` | 192.168.51.1 | 192.168.49.3 |
| D | `00:04` | 192.168.52.1 | 192.168.51.2 |

Adyacencia (árbol):

```
A — {B, C}
B — {A}
C — {A, D}
D — {C}
```

```
        A (ROOT)
       / \
      B   C
           \
            D
```

---

## 1. Tabla de hoy (solo 1 salto)

Tras ANNOUNCE / ANNOUNCE_OK, **sin** REACH:

**A**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:02 | B | 192.168.49.2 | 1 |
| 00:03 | C | 192.168.49.3 | 1 |

**B**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |

**C**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |
| 00:04 | D | 192.168.51.2 | 1 |

**D**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:03 | C | 192.168.51.1 | 1 |

Misma forma de fila que ahora: `UUID · Nombre · IP · Hop`. No se añade columna. En hop > 1 la **IP es la del next-jump** (el vecino a 1 salto por el que hay que escribir), no la IP “real” del destino.

El broadcast **no cambia**: solo fan-out a sesiones de hop 1. Recorrer toda la tabla duplicaría el frame (A mandaría “hacia D” y también a C, que ya es el camino).

---

## 2. Qué lleva el aviso

Por `:8765`, cuando un hijo acaba de entrar (el padre ya hizo `addEntry` y puede mandar ANNOUNCE_OK **y** avisar al resto en el mismo momento).

| Campo | En el cable | Para qué |
|---|---|---|
| UUID del destino | sí | identidad |
| Nombre | sí | se acaba el fallback del UUID corto en L3 |
| Saltos **desde el reportero** | sí | el receptor guarda `hops_reportero + 1` |
| IPv4 del destino | **no** | nadie a 2+ saltos tiene socket con D |

La IP que se escribe en la fila sale del **vecino de ingreso**: ya está en la tabla a hop 1 (la de su ANNOUNCE / OK). Ese es el next-jump.

Split horizon: no se manda de vuelta por donde llegó, ni al propio destino.

Si ya hay ruta al mismo UUID: instalar solo si los hops nuevos son **estrictamente menores**. Si no mejoran, no se repropaga (evita eco en el árbol).

---

## 3. Dos direcciones en cada join

Cuando C acepta a D:

1. **Hacia afuera:** “existe D”. C avisa a todos los hop 1 **excepto D**. Cada quien instala y reenvía igual (menos ingreso).
2. **Hacia el nuevo:** “esto ya había”. C vuelca a D **el resto de su tabla** (A, B, …), cada destino un aviso. D no tiene más vecinos: no reenvía.

D no origina el gossip. Solo ANNOUNCE a C.

---

## 4. Tiempo 1 — solo A, B y C (B y C se conocen)

Arranque: A solo. B se une a A. A añade B hop 1, OK a B. No hay nadie más a quien avisar. B no recibe volcado (A solo conoce a B).

C se une a A:

- A añade C hop 1, OK a C.
- **Afuera:** A avisa “C” a B. B ya tiene a A en `192.168.49.1` hop 1 → fila C: IP `192.168.49.1`, hop **2**. B no tiene más vecinos.
- **Volcado a C:** A avisa “B” a C. C añade B: IP `192.168.49.1`, hop **2**.

Tablas **antes** de D (esto es lo que faltaba en el relato oral):

**A** — igual que §1 (B y C a hop 1).

**B**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |
| 00:03 | C | 192.168.49.1 | 2 |

**C**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |
| 00:02 | B | 192.168.49.1 | 2 |

Unicast B→C: fila C dice next-jump `192.168.49.1` → sesión hop 1 con A. A mira C a hop 1 y entrega. No hay socket B↔C.

---

## 5. Tiempo 2 — D se une a C (el ejemplo largo)

C añade:

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:04 | D | 192.168.51.2 | 1 |

ANNOUNCE_OK a D. Aviso “D” hacia afuera (solo A) y volcado hacia D (A y B).

### 5.1 Afuera: D llega a A y a B

C → A: destino D, hops_reportero = 1. A no usa una IP de D; usa la de **C** (ANNOUNCE de C: `192.168.49.3`).

**A** añade:

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:04 | D | 192.168.49.3 | 2 |

A reenvía a todos hop 1 excepto C → a B. hops_reportero = 2. B usa la IP de **A** (`192.168.49.1`).

**B** añade:

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:04 | D | 192.168.49.1 | 3 |

B no reenvía (solo tiene a A, y A es el ingreso).

### 5.2 Inverso: A y B llegan a D

C vuelca a D lo que no es D.

C → D, destino A, hops_reportero = 1. D usa la IP de **C** (`192.168.51.1`).

**D** añade:

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.51.1 | 2 |

C → D, destino B, hops_reportero = 2 (C llega a B en 2). D otra vez next-jump C.

**D** añade:

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:02 | B | 192.168.51.1 | 3 |

D no reenvía (único vecino = ingreso).

### 5.3 Tablas finales

**A**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:02 | B | 192.168.49.2 | 1 |
| 00:03 | C | 192.168.49.3 | 1 |
| 00:04 | D | 192.168.49.3 | 2 |

**B**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |
| 00:03 | C | 192.168.49.1 | 2 |
| 00:04 | D | 192.168.49.1 | 3 |

**C**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:01 | A | 192.168.49.1 | 1 |
| 00:02 | B | 192.168.49.1 | 2 |
| 00:04 | D | 192.168.51.2 | 1 |

**D**

| UUID | Nombre | IP | Hop |
|---|---|---|---|
| 00:03 | C | 192.168.51.1 | 1 |
| 00:01 | A | 192.168.51.1 | 2 |
| 00:02 | B | 192.168.51.1 | 3 |

Comprobar: de B a D son 3 saltos (B→A→C→D). De D a B igual por el otro lado. De A a D son 2 (A→C→D). Nombres de todos en todas las tablas → L3 ya no inventa el UUID corto.

---

## 6. Unicast en `:8766` (cuando exista)

Broadcast: sigue igual, **solo hop 1**.

Unicast `send(dest, payload)`:

1. Fila de `dest`. Si no hay, no se envía (no se convierte en flood).
2. La IP de la fila es el next-jump. Se escribe en la **sesión de datos del vecino hop 1 que tiene esa IP** (la misma sesión HELLO de siempre).
3. Ese vecino repele: si `dest == yo` → `pop()`; si no, otra vez lookup y un salto. No se recorre el resto de la tabla.

Ejemplos:

| Origen | Destino | Fila | Sesión |
|---|---|---|---|
| B | D | IP 192.168.49.1 hop 3 | A |
| A | D | IP 192.168.49.3 hop 2 | C |
| D | B | IP 192.168.51.1 hop 3 | C |

El frame de datos sí lleva `dest_uuid` (y origen, msg_id, payload). El broadcast actual **no** le pone destino; son dos `msg_type` en el mismo `:8766`.

---

## 7. Qué no es esto

- No es un socket por destino remoto. Un TCP de datos por arista hop 1, como ahora.
- No es casar accept por la IPv4 de un nodo a 2 saltos.
- No sustituye fe80 en el connect al padre (PC-08). La IPv4 de la tabla solo elige **cuál sesión hop 1** usar.
- Si C se cae, hay que borrar a C y a todo lo que solo se alcanzaba por C (D en A y en B). Eso es baja de vecino; no está en este planteo más que como deuda.

---

## 8. Hecho / falta

Hecho: codec REACH, volcado + flood al ANNOUNCE, split horizon, unicast `:8766`, broadcast solo hop 1. Demo L2 muestra hop > 1. Chat L3 sigue en `broadcast()`.

Falta: borrar filas cuando se cae un vecino.
