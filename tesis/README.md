# Monografía PCT — LaTeX

## Anteproyecto (ya escrito — NO duplicar)

**`docs/monography/pre_project/AnteProyecto-Final.pdf`**

Contiene (aprox. 22 páginas):

| Sección | Contenido |
|---|---|
| Resumen / Abstract | Palabras clave, resumen EN/ES |
| Introducción | Contexto post-desastre, estructura documento |
| **Cap. 1** Planteamiento | 1.1–1.6 completo con citas (Tanenbaum, Akyildiz, etc.) |
| **Cap. 2** Marco referencia | 2.1 teórico (def. técnicas/no técnicas), 2.2 estado del arte (AODV, OLSR, Meshtastic, Bridgefy, Briar, BitChat, cuadro comparativo), 2.3 legal (Ley 1581, 1341) |
| **Cap. 3** Metodología | Roles JC/BS, 4 incrementos, validación experimental |
| Cap. 4 Cronograma | 12 semanas |
| Cap. 5 Costos | Presupuesto COP |
| Referencias | Bibliografía completa |

Director: **Elio Higinio Cables Pérez, Ph.D.**

## Continuación (este repo)

**`tesis/pct-monografia.tex`** — solo lo que falta para la monografía de grado:

- Cap. **4 Desarrollo del sistema** (lib-pct-core, L2/L3, app demo)
- Cap. **5 Resultados obtenidos** (EXP-01.., hallazgos)
- **Conclusiones**
- Referencias adicionales de implementación

```bash
cd tesis
pdflatex pct-monografia.tex
pdflatex pct-monografia.tex
```

## Documento final UAN

1. Capítulos 1–3 (y resumen/intro) → **copiar del anteproyecto LaTeX/PDF** sin reescribir.
2. Capítulos 4–5 + Conclusiones → **`pct-monografia.tex`** (renumerar si la plantilla lo exige).
3. Cronograma/Costos del anteproyecto → anexos o eliminar en monografía final (según director).

Cuando entregues la plantilla `.tex` UAN, integramos con `\input{}`.

## Obsoleto

`capitulos/*.tex` y `main.tex` — borrador genérico anterior; ignorar.
