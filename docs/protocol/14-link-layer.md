# 14 — Capa 2: TCP, sentido de la arista y tabla de rutas

**Versión:** 0.3.0-draft  
**Prerequisito:** [04-link-model-go-legacy.md](04-link-model-go-legacy.md) (radio). L1 ya hizo GO + STA.

---

## 1. En una frase

L1 deja dos radios juntos. L2 abre **un TCP en `:8765`**, acuerda **quién es padre y quién hijo**, y por ese mismo socket **mantiene la tabla de rutas**. Las IP no salen de L2. Arriba solo se ven `node_id`. Cuando la tabla ya conoce al vecino, se puede abrir `:8766` para el tráfico de usuario.

Sin catálogo de mensajes de unión. Sin UDP. Sin MAC como identidad.

---

## 2. Tres pasos

```
STA asociado (L1)
    → TCP :8765  (quien tiene STA conecta al gateway del GO padre, p. ej. 192.168.49.1)
    → negociar sentido de la arista
    → hablar: quién soy, sigo vivo, estas son las rutas que conozco
    → (después) TCP :8766 para usuario, usando la tabla
```

Todo nodo con GO escucha `:8765` (y más tarde `:8766`) en su SoftAP. El hijo inicia el connect. Si los dos hicieron STA a la vez, los dos pueden conectar: L2 elige **un** sentido y L1 suelta el STA que no toca.

### Sentido de la arista

Misma componente, primer contacto (mismo depth): **menor `node_id` = padre**.  
Si ya hay árbol: el de menor depth/hop es padre.

Un `UPSTREAM` y 0..N `DOWNSTREAM`. No dos padres.

---

## 3. Tabla de rutas (vive en L2)

L2 es quien tiene el socket y por tanto la **IP local del vecino**. Esa IP no se publica hacia L3 ni a la app: solo sirve para escribir en el TCP correcto.

Vista hacia arriba (solo nids):

| destino (`node_id`) | siguiente salto (`node_id`) | saltos |
|---|---|---|
| vecino directo | él mismo | 1 |
| yo | — | 0 |
| alguien más allá | el vecino por el que se llega | 2..N |

Por debajo, L2 guarda junto al siguiente salto: IP, `:8765`, y si ya hay `:8766`. Eso no cruza el límite de capa.

Cada vecino, por `:8765`, manda un resumen de *destinos que conozco → saltos*. El receptor fusiona: “para ir a X, el siguiente soy yo hacia ese vecino”. Anti-ciclo: no instalar una ruta que vuelva por donde vino; tope de saltos 7.

Cadena A (padre) — B — C:

- En A: C se alcanza pasando por B.
- En B: A y C son vecinos (o uno de ellos a 1 salto).
- En C: A se alcanza pasando por B.

Si se cae el TCP o el STA, L2 borra al vecino y las rutas que solo existían por él. L1 puede volver a buscar; L2 no toca el GO.

---

## 4. Puerto de usuario `:8766`

Cuando hay fila en la tabla para un vecino directo y `:8765` está vivo, se abre `:8766` hacia esa misma IP. Ahí solo va payload de aplicación (chat, etc.).

Si `:8766` se cae, se reabre usando la IP que L2 ya tiene en la tabla. No se reinicia el nodo ni el GO.

Control (`:8765`) y usuario (`:8766`) no comparten socket: en Android un único TCP mezcla mal keep-alive y ráfagas.

---

## 5. Qué hay que implementar (y nada más)

1. Listen `:8765` en el GO; connect `:8765` tras STA.
2. Intercambio mínimo: `node_id` + depth + “estas rutas”.
3. Acordar padre/hijo; un STA de más → pedirle a L1 que lo cierre.
4. Tabla nid → siguiente nid (IP solo interna).
5. Keep-alive en `:8765`; silencio → vecino muerto.
6. Luego listen/connect `:8766` para los vecinos de la tabla.

Si el scan L1 ve un `node_id` **que no está en la tabla**, no se une al instante: se investiga con un **DFS por vecinos** (`WHO`). Un salto de 1 es más cercano que un nodo que solo aparece a 2..N en la tabla; el token no se inunda: cada nodo pregunta a **un** vecino (hijos antes que el padre), espera, y sigue. Si dos ondas del **mismo evento** se cruzan, se hace **merge**: se queda la más cercana (menos hops desde su origen; empate: menor `node_id`) y la otra cede (`MERGE`) para no armar tormenta. Si alguien lo conoce, era anuncio residual. Si nadie, es **otro árbol** (`SEE` / `GOING` recorren igual). Un brazo **con radio** se ofrece; no lo decide un root. Si hace falta, `CLIMB` es local (mis hijos → mi padre). No se asume que el otro “arrancó mal”.

---

## 6. Fuera de L2

| No | Dónde |
|---|---|
| `createGroup`, DNS-SD, STA | L1 |
| Reenviar bytes de usuario a un nid no vecino | L3 pide a L2 “envía a este nid”; L2 usa la tabla y `:8766` |
| Elegir SoftAP / PSK | L1 |
