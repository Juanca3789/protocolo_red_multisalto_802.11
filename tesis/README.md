# Monografía PCT — LaTeX (plantilla UAN)

**Archivo principal:** `pct-monografia.tex`

```bash
cd tesis
python3 generar_contenido.py   # opcional: regenerar texto del anteproyecto
pdflatex pct-monografia.tex
pdflatex pct-monografia.tex
```

Salida: `pct-monografia.pdf`

## Formato

- Clase `article`, **todo 12 pt** (sin `\chapter` gigante ni `\large`)
- Encabezados: `\capitulo{}` / `\seccion{}` → negrita 12 pt + `\newpage` donde corresponde
- Carta, Times (`mathptmx`), doble espacio, sangría 1,25 cm

## Contenido

| Archivo | Origen |
|---------|--------|
| `contenido-anteproyecto.tex` | Generado desde `docs/monography/pre_project/AnteProyecto-Final.pdf` |
| `contenido-desarrollo.tex` | Desarrollo `lib-pct-core` + resultados EXP |
| `generar_contenido.py` | Script de extracción (pdftotext) |

## Obsoleto

`main.tex`, `capitulos/*.tex` — no usar.
