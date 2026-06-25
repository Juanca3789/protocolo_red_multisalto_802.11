# Convención de nombres — apps y prototipos Android

**Proyecto:** PCT (Protocolo de Control Topológico)  
**Versión:** 1.0

Esta convención aplica a módulos en `pct/android/` (o proyecto Gradle equivalente). Android Studio usa el **nombre del módulo** como **carpeta** en disco; el **package / applicationId** va en `build.gradle.kts` y el **nombre visible** en `res/values/strings.xml`.

---

## 1. Resumen rápido

| Capa | Formato | Ejemplo (EXP-01 GO + STA legacy) |
|---|---|---|
| **Carpeta / módulo Gradle** | `{tier}-{id}-{slug}` | `proto-exp01-gostat` |
| **Package / namespace** | `co.edu.uan.pct.{tier}.{id}.{slug}` | `co.edu.uan.pct.proto.exp01.gostat` |
| **applicationId** | Igual que package (prototipos) | `co.edu.uan.pct.proto.exp01.gostat` |
| **Nombre en launcher** | `PCT · {ID} · {Título}` | `PCT · EXP01 · GO+STA` |

---

## 2. Raíz de package (fija)

```
co.edu.uan.pct
```

| Segmento | Significado |
|---|---|
| `co.edu.uan` | Universidad Antonio Nariño |
| `pct` | Protocolo de Control Topológico |

No usar `com.example` ni paquetes genéricos en prototipos del grado.

---

## 3. Tiers (tipo de módulo)

Prefijo de carpeta y segmento de package tras `pct`:

| Tier | Carpeta | Package segment | Uso |
|---|---|---|---|
| **proto** | `proto-…` | `.proto.` | Apps de prueba / spikes / EXP |
| **cap** | `cap-…` | `.cap.` | Pruebas de capacidad hardware (CAP-Txx) |
| **lib** | `lib-…` | `.lib.` | Código compartido (framing PCT1, modelos) |
| **app** | `app-…` | `.app.` | Demo integrada (APK de grado) |

Solo debe existir **un** módulo `app-demo` (o `app-pct`) como entrega final.

---

## 4. Identificador de experimento (`id`)

Alineado con `docs/protocol/11-validation-matrix.md`:

| ID doc | `id` en nombre | Descripción corta |
|---|---|---|
| EXP-01 | `exp01` | Dos GO + STA legacy |
| EXP-02 | `exp02` | DNS-SD en GO |
| EXP-03 | `exp03` | Cadena 3 nodos |
| EXP-04 | `exp04` | Unión tardía |
| EXP-05 | `exp05` | Caída puente |
| EXP-06 | `exp06` | ISLAND cerca de LEAF |
| CAP-T01… | `cap01`… | Capacidades (`10-capabilities-and-profiles.md`) |

Formato: **minúsculas**, sin guión interno (`exp01`, no `exp-01`).

---

## 5. Slug (descripción corta)

Resumen del foco en **una palabra o compuesto corto**, solo `a-z`, sin espacios.

| Slug | Significado |
|---|---|
| `gostat` | P2P GO + STA legacy upstream |
| `dnsd` | DNS-SD add/discover |
| `chain3` | Cadena de 3 nodos |
| `latejoin` | Unión tardía |
| `bridgefail` | Caída de nodo puente |
| `leafseek` | ISLAND cerca de LEAF |
| `core` | Librería núcleo protocolo |
| `demo` | App demostrativa completa |

Máximo recomendado: **12 caracteres**.

---

## 6. Reglas de carpeta / módulo (Android Studio)

```
{tier}-{id}-{slug}
```

**Reglas:**

- Solo minúsculas, números y guiones `-`.
- Sin espacios ni guiones bajos (evita fricción con Gradle y Studio).
- Un módulo = una carpeta = un `include()` en `settings.gradle.kts`.

**Ejemplo `settings.gradle.kts`:**

```kotlin
rootProject.name = "pct-android"
include(":lib-pct-core")
include(":proto-exp01-gostat")
include(":proto-exp02-dnsd")
include(":app-demo")
```

**Estructura en disco:**

```
pct/android/
├── settings.gradle.kts
├── build.gradle.kts
├── lib-pct-core/
├── proto-exp01-gostat/
├── proto-exp02-dnsd/
└── app-demo/
```

---

## 7. Package y namespace

```
co.edu.uan.pct.{tier}.{id}.{slug}
```

**Ejemplos:**

| Módulo | namespace / package |
|---|---|
| `proto-exp01-gostat` | `co.edu.uan.pct.proto.exp01.gostat` |
| `proto-exp02-dnsd` | `co.edu.uan.pct.proto.exp02.dnsd` |
| `lib-pct-core` | `co.edu.uan.pct.lib.core` |
| `app-demo` | `co.edu.uan.pct.app.demo` |

**Reglas:**

- `applicationId` = package en **prototipos** (instalar varias apps a la vez en el mismo teléfono).
- En `app-demo` puede mantenerse igual o añadir sufijo `.beta` mientras no sea release final.
- Actividades de prueba: `…gostat.MainActivity`, `…gostat.ui.GoStaActivity`.

---

## 8. Nombre visible de la app (launcher)

En `res/values/strings.xml`:

```xml
<string name="app_name">PCT · EXP01 · GO+STA</string>
```

**Plantilla:**

```
PCT · {ID_MAYÚS} · {Título legible}
```

| Módulo | `app_name` |
|---|---|
| `proto-exp01-gostat` | `PCT · EXP01 · GO+STA` |
| `proto-exp02-dnsd` | `PCT · EXP02 · DNS-SD` |
| `proto-exp03-chain3` | `PCT · EXP03 · Chain×3` |
| `lib-pct-core` | *(sin launcher; librería)* |
| `app-demo` | `PCT Mesh` |

**Caracteres:** usar `·` (punto medio) como separador; evitar `/` y `\`. En APK de grado el nombre puede ser más amigable (`PCT Mesh`).

---

## 9. Nombre del proyecto raíz en Android Studio

| Campo | Valor recomendado |
|---|---|
| Project name | `pct-android` |
| Root folder | `pct/android/` |

No nombrar el proyecto raíz solo `app` ni `protocolo`.

---

## 10. Tabla completa de prototipos planificados

| Módulo (carpeta) | Package | app_name | EXP/CAP |
|---|---|---|---|
| `proto-exp01-gostat` | `co.edu.uan.pct.proto.exp01.gostat` | PCT · EXP01 · GO+STA | EXP-01 |
| `proto-exp02-dnsd` | `co.edu.uan.pct.proto.exp02.dnsd` | PCT · EXP02 · DNS-SD | EXP-02 |
| `proto-exp03-chain3` | `co.edu.uan.pct.proto.exp03.chain3` | PCT · EXP03 · Chain×3 | EXP-03 |
| `proto-exp04-latejoin` | `co.edu.uan.pct.proto.exp04.latejoin` | PCT · EXP04 · Late join | EXP-04 |
| `proto-exp05-bridgefail` | `co.edu.uan.pct.proto.exp05.bridgefail` | PCT · EXP05 · Bridge fail | EXP-05 |
| `proto-exp06-leafseek` | `co.edu.uan.pct.proto.exp06.leafseek` | PCT · EXP06 · Leaf seek | EXP-06 |
| `cap-cap01-goconcurrent` | `co.edu.uan.pct.cap.cap01.goconcurrent` | PCT · CAP01 · GO∥STA | CAP-T01… |
| `lib-pct-core` | `co.edu.uan.pct.lib.core` | — | Librería |
| `app-demo` | `co.edu.uan.pct.app.demo` | PCT Mesh | Entrega final |

---

## 11. Convención para recursos y logs

| Elemento | Convención | Ejemplo |
|---|---|---|
| Log tag | `PCT/{ID}/{slug}` | `PCT/EXP01/gostat` |
| Prefijo prefs | `pct_{id}_{slug}_` | `pct_exp01_gostat_` |
| Canal notificación | `pct_{id}` | `pct_exp01` |

---

## 12. Qué no hacer

| Evitar | Motivo |
|---|---|
| `app`, `test`, `prueba1` | No identifica EXP ni protocolo |
| Carpetas con espacios | Android Studio / Gradle |
| Mismo `applicationId` en dos prototipos | No se pueden instalar juntas |
| Package distinto sin razón del applicationId | Confusión en logs y adb |
| Reusar módulo para otro EXP | Crear `proto-exp0X-nuevo` |

---

## 13. Crear un prototipo nuevo (checklist)

1. Elegir `EXP-xx` o `CAP-xx` en la matriz de validación.
2. Definir `slug` (una palabra compuesta).
3. Crear módulo `proto-expXX-slug` en Android Studio.
4. Setear `namespace` y `applicationId` = `co.edu.uan.pct.proto.expXX.slug`.
5. `app_name` = `PCT · EXPXX · Título`.
6. Añadir fila en la tabla §10 de este documento.
7. README breve en el módulo: objetivo, criterio PASS, dispositivos.
