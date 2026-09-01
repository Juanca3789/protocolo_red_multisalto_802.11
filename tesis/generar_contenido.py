#!/usr/bin/env python3
"""Extrae párrafos completos del anteproyecto PDF → LaTeX (12 pt, sin títulos grandes)."""
import re
import subprocess
import unicodedata

PDF = "docs/monography/pre_project/AnteProyecto-Final.pdf"
OUT = "tesis/contenido-anteproyecto.tex"

# Líneas 0-based de pdftotext (ver `python3 -c ...` de depuración)
SLICES = {
    "resumen": (213, 228),
    "abstract": (230, 244),
    "intro": (246, 271),
    "desc_problema": (277, 314),
    "formulacion": (317, 330),
    "justificacion": (333, 374),
    "obj_general": (379, 384),
    "obj_especificos": (387, 398),
    "alcances": (402, 420),
    "limitaciones": (423, 441),
    "marco_teorico": (446, 563),
    "estado_arte": (572, 666),
    "marco_legal": (680, 702),
    "metodologia": (705, 855),
}


def strip_leading_titles(p: str) -> str:
    """Quita títulos duplicados al inicio del párrafo."""
    patterns = [
        r"^Descripción Del Problema\s+",
        r"^Descripcion Del Problema\s+",
        r"^Formulación del problema\s+",
        r"^Formulacion del problema\s+",
        r"^Justificación\s+",
        r"^Justificacion\s+",
        r"^Objetivos\s+Objetivo General\s+",
        r"^Objetivos Específicos\s+",
        r"^Objetivos Especificos\s+",
        r"^Alcances\s+",
        r"^Limitaciones\s+",
    ]
    for pat in patterns:
        p = re.sub(pat, "", p, flags=re.I)
    return p.strip()


def fix_pdf_unicode(s: str) -> str:
    """Corrige artefactos de pdftotext (ı + acento combinante, etc.)."""
    s = unicodedata.normalize("NFC", s)
    s = s.replace("\u0131", "i")  # dotless i → i (español)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return s


def esc(s: str) -> str:
    s = fix_pdf_unicode(s)
    repl = {
        "\\": r"\textbackslash{}",
        "&": r"\&",
        "%": r"\%",
        "$": r"\$",
        "#": r"\#",
        "_": r"\_",
        "{": r"\{",
        "}": r"\}",
    }
    for a, b in repl.items():
        s = s.replace(a, b)
    s = s.replace("≥", r"$\geq$")
    s = s.replace("—", "---")
    s = s.replace("–", "--")
    s = re.sub(r"\(\(", "(", s)
    s = re.sub(r"\)\)", ")", s)
    return s.strip()


def load_lines() -> list[str]:
    raw = subprocess.check_output(["pdftotext", PDF, "-"], text=True)
    return raw.splitlines()


def is_noise(line: str) -> bool:
    s = line.strip().lstrip("\f").strip()
    if not s:
        return True
    if re.fullmatch(r"\d+", s):
        return True
    if re.match(r"^\.+\s*$", s):
        return True
    if " . . . " in s and len(s) > 30:
        return True
    if re.match(r"^(\d+\.)+\d*\.?$", s):
        return True
    if re.match(r"^Cuadro \d", s):
        return True
    return False


def join_paragraphs(chunk: list[str]) -> list[str]:
    paras: list[str] = []
    buf: list[str] = []
    for line in chunk:
        if is_noise(line):
            if buf and buf[-1].rstrip()[-1:] in ".?!:;»\"":
                paras.append(" ".join(buf))
                buf = []
            continue
        s = line.strip().lstrip("\f").strip()
        if re.match(r"^(Entregables|No entregables)$", s, re.I):
            if buf:
                paras.append(" ".join(buf))
                buf = []
            paras.append(f"\\textbf{{{esc(s)}}}")
            continue
        # Omitir líneas que son solo títulos de subsección repetidos
        if re.match(
            r"^(Descripción|Descripcion|Formulación|Formulacion|Justificación|Justificacion|"
            r"Objetivos|Objetivo General|Objetivos Específicos|Objetivos Especificos|"
            r"Alcances|Limitaciones|Marco Teórico|Marco Teorico|Estado del Arte|"
            r"Marco Legal|Definiciones|Desastre Natural|Resiliencia|Comunicación|"
            r"Comunicacion|Red de|Red Ad|Redes Multisalto|Protocolo Distribuido|"
            r"Topología|Topolog|Continuidad|Sockets|Ad hoc On|Optimized Link|"
            r"Meshtastic|Bridgefy|BitChat|Análisis comparativo|Analisis comparativo|"
            r"Ley \d|Roles y responsabilidades|Fases del proyecto|Incremento \d|Técnicas|"
            r"Tecnicas)$",
            s,
            re.I,
        ):
            continue
        buf.append(s)
    if buf:
        paras.append(" ".join(buf))
    return [strip_leading_titles(p) for p in paras if len(p) > 20]


def write_paras(f, paras):
    for p in paras:
        if p.startswith("\\textbf"):
            f.write(p + "\n\n")
        else:
            f.write(esc(p) + "\n\n")


def slice_lines(lines, start, end):
    return lines[start:end]


def main():
    lines = load_lines()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("% Generado desde AnteProyecto-Final.pdf — párrafos completos\n\n")

        f.write("\\capitulo{Resumen}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["resumen"])))

        f.write("\\newpage\n\\capitulo{Abstract}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["abstract"])))

        f.write("\\newpage\n\\capitulo{Introducción}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["intro"])))

        f.write("\\newpage\n\\capitulo{Antecedentes}\n\n")
        f.write("\\seccion{Descripción del problema}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["desc_problema"])))
        f.write("\\seccion{Formulación del problema}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["formulacion"])))

        f.write("\\newpage\n\\capitulo{Justificación}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["justificacion"])))

        f.write("\\newpage\n\\capitulo{Objetivos}\n\n")
        f.write("\\seccion{Objetivo general}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["obj_general"])))
        f.write("\\seccion{Objetivos específicos}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["obj_especificos"])))
        f.write("\\seccion{Alcances}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["alcances"])))
        f.write("\\seccion{Limitaciones}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["limitaciones"])))

        f.write("\\newpage\n\\capitulo{Marco teórico}\n\n")
        f.write("\\seccion{Fundamentos conceptuales y tecnológicos}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["marco_teorico"])))
        f.write("\\seccion{Estado del arte}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["estado_arte"])))
        f.write("\\seccion{Marco legal}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["marco_legal"])))

        f.write("\\newpage\n\\capitulo{Diseño metodológico}\n\n")
        write_paras(f, join_paragraphs(slice_lines(lines, *SLICES["metodologia"])))

    print(f"OK → {OUT} ({sum(1 for _ in open(OUT))} líneas)")


if __name__ == "__main__":
    import os

    os.chdir("/home/Carlos_378/Documents/protocolo_red_multisalto_802.11")
    main()
