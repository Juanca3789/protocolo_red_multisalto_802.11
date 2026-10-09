# lib-pct-core

Android Library del núcleo PCT. Proyecto **solo librería** (sin módulo `:app`).

| Campo | Valor |
|--------|--------|
| Carpeta | `pct/library/` |
| Módulo | `:lib-pct-core` |
| Namespace | `co.uan.pct.lib.core` |
| Maven Local | `co.uan.pct:core:1.1.0` |
| minSdk | 31 |

Especificación L1 de radio: [`docs/prototype/proto-exp01-gostat/`](../../docs/prototype/proto-exp01-gostat/README.md).

## API

```kotlin
val mesh = MultiHopProtocol("pct").attach(applicationContext)
// tras permisos:
mesh.start()
// state, connectedMacs, parentMac (StateFlow)
mesh.stop()
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
