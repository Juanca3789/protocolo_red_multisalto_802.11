# 15 — Capa 3: reenvío de usuario (solo nids)

**Versión:** 0.3.0-draft  
**Prerequisito:** [14-link-layer.md](14-link-layer.md)

---

## 1. Reparto

La **tabla de rutas** está en L2: L2 conoce sockets e IP. L3 no guarda IPs.

L3 solo ve `node_id`. Su trabajo: “este payload de usuario va al nid D”. Pregunta a L2 el siguiente salto (otro nid) y le pide enviarlo por **`:8766`**. Si D soy yo, lo entrega a la app.

```
app → MeshSocket.send(destino_nid, bytes)   // nid, no IP
        → L2: siguiente = tabla[destino]
        → L2 escribe en :8766 del vecino `siguiente`
        → el MeshSocket de ese vecino repite hasta llegar
```

Sin NAT. Sin enrutar por IP de destino final.

---

## 2. Reglas

- Un paquete lleva `destino_nid` y un tope de saltos (máx. 7). Si el tope llega a 0, se descarta.
- No reenviar por el mismo vecino del que llegó si eso cierra un ciclo.
- L3 no abre puertos ni conoce `192.168.49.x`.
- Si L2 no tiene ruta a D, no hay envío (no se inventa un flood).

Detalle de fusión de anuncios y límites: [07-routing-and-topology.md](07-routing-and-topology.md). La autoridad de la tabla es L2 ([14](14-link-layer.md) §3).

---

## 3. Ejemplo (vista L3)

A ← B ← C. Chat de C hacia A:

| Nodo | L3 decide | L2 hace |
|---|---|---|
| C | destino A, siguiente B | escribe `:8766` hacia B |
| B | destino A, siguiente A | escribe `:8766` hacia A |
| A | destino soy yo | entrega a la app |

Las IP de cada hop solo existen dentro de L2.
