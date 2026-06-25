# Prototipo EXP-01 — Especificaciones de software

**Módulo:** `pct/prototype/proto-exp01-gostat`  
**applicationId:** `co.uan.pct.proto.exp01.gostat`  
**Versión app:** 1.0  
**Alcance documentado:** dos dispositivos (sin tercer salto ni TCP de control aún)

Este directorio describe **cómo se comporta el software implementado**, no la arquitectura general del protocolo PCT. Para el diseño teórico del incremento 1, ver [`docs/protocol/`](../../protocol/).

---

## Documentos

| Archivo | Contenido |
|---|---|
| [spec-mdns-dns-sd.md](spec-mdns-dns-sd.md) | DNS-SD sobre Wi‑Fi Direct (MDNS/DNS-SD P2P): servicio `_pct-ctrl`, instance, TXT, descubrimiento |
| [spec-p2p-wifi-direct.md](spec-p2p-wifi-direct.md) | P2P: GO local, APIs Android, permisos, restricciones de laboratorio |
| [spec-legacy-sta.md](spec-legacy-sta.md) | STA legacy: asociación WPA2 al GO del padre, permisos, flujo de usuario |

---

## Flujo operativo (dos teléfonos)

```
Dispositivo A (raíz)                Dispositivo B (miembro)
────────────────────                ────────────────────────
Iniciar raíz                        Buscar padres (GO apagado)
  → createGroup()                     → discoverPeers + discoverServices
  → addLocalService(_pct-ctrl)        → lista candidatos (ventana 5 s)
                                      → usuario elige 1 padre
                                      → Conectar STA (1×) al GO de A
                                      → createGroup() + addLocalService (BRIDGE)
```

**Anti-tormenta:** el escaneo acumula candidatos y **no** auto-conecta; solo una `requestNetwork` al pulsar **Conectar STA (1×)**.

---

## Fuera de alcance en v1.0

- Servicio `_pct-seek._tcp`
- Mensajes TCP (`HELLO`, `JOIN_COMMIT`, …)
- Cadena de tres nodos
- Cifrado de credenciales en instance DNS-SD (solo laboratorio)

---

## Trazabilidad

| Especificación | Código principal |
|---|---|
| DNS-SD | `DnsSdRepository.kt`, `PctInstanceCodec.kt`, `PctTxtParser.kt` |
| P2P GO | `GoRepository.kt`, `P2pChannelHolder.kt` |
| STA legacy | `LegacyStaRepository.kt` |
