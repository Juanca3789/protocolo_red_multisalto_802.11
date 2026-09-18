# 14 — Capa 2: TCP, sentido de la arista y tabla de rutas

**Versión:** 0.4.0 (implementado en `lib-pct-core` 2.2.0)  
**Prerequisito:** [04-link-model-go-legacy.md](04-link-model-go-legacy.md) (radio). L1 ya hizo GO + STA.

---

## 1. En una frase

L1 deja dos radios juntos y le entrega a L2 **un `Uplink`**: a qué dirección abrir el TCP y cómo atar el socket a la red STA. L2 abre **un TCP en `:8765`** y por ese mismo socket **mantiene la tabla de rutas**. Las IP no salen de L2. Arriba solo se ven `node_id`. Con el vecino en la tabla, el hijo abre `:8766` para el tráfico de usuario.

Sin catálogo de mensajes de unión. Sin UDP. Sin MAC como identidad. L2 no sabe de Wi‑Fi: se prueba entero en JVM sobre `127.0.0.1` con el mismo código que corre en el teléfono.

---

## 2. Tres pasos

```
STA asociado (L1)
    → Uplink = fe80:: del GO padre con ámbito de la interfaz STA (EUI-64 de su BSSID)
    → TCP :8765  (quien tiene STA conecta; el socket va atado a la red STA)
    → hablar: quién soy, sigo vivo, estas son las rutas que conozco
    → TCP :8766 (lo abre el hijo; empieza con sus 16 B de nid)
```

Todo nodo con GO escucha `:8765` y `:8766` en su SoftAP. El hijo inicia los dos connect.

**Por qué `fe80::` y no `192.168.49.1`:** [02-platform-constraints.md](02-platform-constraints.md) PC-08. Todo GO es `.1`; en cuanto el hijo levanta su propio grupo, `.1` es una dirección local suya y el TCP al padre muere. La de enlace local del padre lleva el ámbito de la interfaz y no choca.

### Sentido de la arista

**El sentido del socket es el sentido de la arista.** Quien hizo STA conecta y es **hijo**; quien acepta en su GO es **padre**. No hay negociación por mensajes ni comparación de depth.

Bucle (los dos hicieron STA al otro a la vez → dos TCP con sentidos opuestos): regla fija e idéntica en ambos, **el `node_id` mayor es el hijo**. El menor se queda de padre, descarta su TCP saliente y le pide a L1 soltar su STA. Se detecta sin mensajes: si acepto una conexión entrante del mismo nodo al que apunta mi `Uplink`, es bucle, sin importar el orden en que lleguen los `HI`.

Un `UPSTREAM` y 0..N `DOWNSTREAM`. No dos padres.

---

## 3. Mensajes de control (`:8765`, una línea por mensaje)

| Línea | Quién | Para qué |
|---|---|---|
| `HI nid depth tree rutas` | ambos, al abrir | quién soy, mi profundidad, la raíz de mi árbol y `destino/saltos,...` |
| `TAB rutas` | ambos, al cambiar la tabla | resumen de destinos que conozco |
| `PING n` / `PONG n` | ambos, cada 5 s | silencio de 15 s → vecino muerto |

Nada más. Una línea que no sea de estas se ignora y se sigue leyendo.

---

## 4. Tabla de rutas (vive en L2)

L2 es quien tiene el socket y por tanto la dirección del vecino. Esa dirección no se publica hacia L3 ni a la app: solo sirve para escribir en el TCP correcto.

Vista hacia arriba (solo nids):

| destino (`node_id`) | siguiente salto (`node_id`) | saltos |
|---|---|---|
| vecino directo | él mismo | 1 |
| yo | — | 0 |
| alguien más allá | el vecino por el que se llega | 2..N |

Cada vecino, por `:8765`, manda un resumen de *destinos que conozco → saltos*. El receptor fusiona: “para ir a X, el siguiente soy yo hacia ese vecino”. Anti-ciclo: no instalar una ruta que vuelva por donde vino; tope de saltos 7.

Cadena A (padre) — B — C:

- En A: C se alcanza pasando por B.
- En B: A y C son vecinos.
- En C: A se alcanza pasando por B.

Si se cae el TCP o el STA, L2 borra al vecino y las rutas que solo existían por él. El nodo que perdió a su padre vuelve a ser raíz de su subárbol; L1 puede volver a buscar en el pulso. L2 no toca el GO.

---

## 5. Puerto de usuario `:8766`

Lo abre **el hijo** hacia el padre y lo primero que escribe son sus **16 bytes de `node_id`**; así el padre sabe de quién es el socket sin emparejar por IP. Un solo TCP de datos por arista, en los dos sentidos. Ahí solo va payload de aplicación (chat, etc.), enmarcado según [16-packet-envelope.md](16-packet-envelope.md).

Si `:8766` se cae y `:8765` sigue vivo, el hijo lo reabre. No se reinicia el nodo ni el GO. Control y usuario no comparten socket: en Android un único TCP mezcla mal keep-alive y ráfagas.

---

## 6. Árboles que no se vieron al arrancar

No hay protocolo de fusión en L2. Cada raíz hace un **pulso** de búsqueda de 5 s en un instante al azar de cada minuto ([04-link-model-go-legacy.md](04-link-model-go-legacy.md) §4). Si en ese pulso ve el anuncio de otro GO y no tiene padre, hace STA igual que en el arranque; el TCP y la tabla hacen el resto. Dos raíces que pulsen a la vez no se ven; el azar lo resuelve al minuto siguiente.

---

## 7. Fuera de L2

| No | Dónde |
|---|---|
| `createGroup`, DNS-SD, STA, `fe80::` del padre | L1 |
| Reenviar bytes de usuario a un nid no vecino | L3 pide a L2 “envía a este nid”; L2 usa la tabla y `:8766` |
| Elegir SoftAP / PSK | L1 |

---

## 8. Verificación sin radio

`LinkLayerLoopbackTest` levanta el `LinkLayer` real en `127.0.0.1` con puertos efímeros y un `Uplink` que apunta al padre:

- dos nodos, chat ida y vuelta;
- cadena de tres, mensaje A→C y C→A reenviado por B sin entregarse en B;
- caída de C: A pierde la ruta;
- los dos hacen STA al otro: queda una sola arista, el menor de padre, y el chat funciona.
