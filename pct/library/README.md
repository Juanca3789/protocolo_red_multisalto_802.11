# lib-pct-core

Android Library del núcleo PCT. Proyecto **solo librería** (sin módulo `:app`).

| Campo | Valor |
|--------|--------|
| Carpeta | `pct/library/` |
| Módulo | `:lib-pct-core` |
| Namespace | `co.uan.pct.lib.core` |
| Maven Local | `co.uan.pct:core:1.1.0` |
| minSdk | 31 |

## API

```kotlin
val pct = PctCore.create()
pct.init(applicationContext)
// tras permisos:
pct.start() // scan → join | root
// observar pct.phase / pct.topology / pct.events
pct.close()
```

## Publicar

```bash
cd pct/library
./gradlew :lib-pct-core:publishToMavenLocal
```

## Consumir

```toml
[versions]
pctCore = "1.1.0"
[libraries]
pct-core = { group = "co.uan.pct", name = "core", version.ref = "pctCore" }
```

```kotlin
implementation(libs.pct.core)
```

Requiere `mavenLocal()` en repositorios.
