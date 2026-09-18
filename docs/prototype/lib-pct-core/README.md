# lib-pct-core — Estado vs capas formales

**Artefacto Maven:** `co.uan.pct:core` **2.2.0**  
**Código:** `pct/library/lib-pct-core/`  
**App demo:** `pct/app/app-demo/`

---

## Documentación de diseño (target)

| Capa | Spec |
|---|---|
| Modelo OSI / mapa | [../protocol/13-layer-model.md](../protocol/13-layer-model.md) |
| L2 enlace | [../protocol/14-link-layer.md](../protocol/14-link-layer.md) |
| L3 red | [../protocol/15-network-layer.md](../protocol/15-network-layer.md) |
| Envoltorio paquetes | [../protocol/16-packet-envelope.md](../protocol/16-packet-envelope.md) |
| Roadmap | [../protocol/17-implementation-roadmap.md](../protocol/17-implementation-roadmap.md) |

---

## Qué hay hoy

### L1 — `physical/PhysicalLayer`

- Arranque: limpia la radio (búsqueda, anuncio, STA, grupo residual) y busca **10 s sin grupo propio**.
- Si llega un TXT `_pct-ctrl._tcp` con SSID/clave: STA al padre → publica un **`Uplink`** para L2 → levanta su propio grupo y anuncia.
- Si no: crea grupo, anuncia y **se queda callado**. Pulso de 5 s en un instante al azar de cada minuto; si no tiene padre y ve TXT, se asocia.
- `discoverServices` se relanza cada 5 s (cadencia del prototipo EXP-01); relanzarlo más seguido aborta las consultas GAS que traen el TXT.
- El `Uplink` lleva la **`fe80::` del GO padre** (EUI-64 del BSSID, con ámbito de la interfaz STA) y ata cada socket a la red STA. Motivo: PC-08 en [../protocol/02-platform-constraints.md](../protocol/02-platform-constraints.md).
- Cierre: tumba búsqueda, anuncio, STA y grupo (nada residual).

### L2 — `link/LinkLayer` (sin Android)

- Entrada: `StateFlow<Uplink?>` + `onLoop` (pedir a L1 soltar la STA). Nada de `Context`.
- `:8765` control: `HI`, `TAB`, `PING`/`PONG`. Sentido de la arista = sentido del socket.
- `:8766` datos: lo abre el hijo, empieza con 16 B de nid, se reabre mientras el control viva.
- Tabla nid → siguiente nid (`RouteTable`), tope 7 saltos, gossip `TAB` al cambiar.
- Bucle hijo↔hijo: el nid mayor es hijo; detectado por `Uplink.parentNid`, sin mensajes.

### L3 — `net/MeshSocket`

- Envío por nid: consulta la tabla, escribe en el `:8766` del siguiente salto. TTL 7. No devuelve el paquete por donde vino.

---

## Verificación

`./gradlew :lib-pct-core:testDebugUnitTest` (JVM, sin dispositivo):

| Prueba | Qué cubre |
|---|---|
| `LinkLayerLoopbackTest` | el `LinkLayer` real sobre TCP en `127.0.0.1`: chat 2 nodos, cadena de 3 con reenvío, caída de vecino, bucle mutuo |
| `Eui64Test` | `fe80::` a partir de los BSSID reales capturados en el A24 y el M23 |
| `RouteTableTest`, `MeshSocketTest`, `TwoDeviceLabTest` | tabla, reenvío, enmarcado `:8766` |
| `CtrlCodecTest`, `DnsSdTxtTest` | codificación de control y de TXT |

Lo que solo se puede verificar con dos teléfonos: DNS-SD (TXT), asociación STA, `fe80::` real del GO. Protocolo de prueba en `GuiaAppDemo.kt`: A arranca y anuncia; B arranca cuando A ya anunció.

---

## Prototipo EXP-01 (origen)

Comportamiento de laboratorio documentado en [../prototype/proto-exp01-gostat/](../prototype/proto-exp01-gostat/).
