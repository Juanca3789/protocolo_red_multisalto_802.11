# PCT — Presentación técnica

Diapositivas interactivas en **React + Vite** con tema de redes. Cada slide se captura como imagen y se ensambla en un PDF.

## Contenido (9 diapositivas)

1. Portada
2. Arquitectura
3. Modo de conexión (GO + STA · P2P hijos / WiFi padre)
4. OSI emulado
5. Mensajes dirigidos y broadcast
6. Desconexiones en Bridge
7. Unión de subárboles independientes
8. Biblioteca lib-pct-core
9. Resumen

## Uso

```bash
cd presentation
npm install
npm run dev
```

Abre la URL que muestra Vite (normalmente `http://localhost:5173`).

| Tecla | Acción |
|---|---|
| `→` / `Espacio` | Siguiente diapositiva |
| `←` | Anterior |
| `Home` / `End` | Primera / última |

Botón **Exportar PDF** — genera `pct-presentacion.pdf` capturando cada diapositiva como JPEG vía html2canvas + jsPDF.

## Build estático

```bash
npm run build
npm run preview
```

## Stack

- React 18
- Vite 6
- html2canvas + jsPDF (exportación)
