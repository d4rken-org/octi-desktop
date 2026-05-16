# octi-desktop

Compose Multiplatform desktop companion for [Octi](https://github.com/d4rken-org/octi).

- **Targets**: Windows, macOS (Intel + Apple Silicon), Linux x86_64
- **Stack**: Kotlin 2.3 · Compose Multiplatform 1.7 · Ktor Client 3.4 · Tink Java 1.16
- **Runtime**: JDK 21 (bundled via jpackage)

## MVP scope

- Read-only viewer for all peer modules (power, meta, wifi, apps, connectivity, clipboard, files)
- Desktop participates as a device for **meta** and **clipboard**
- Full read/write blob support for `modules-files` (GCM-SIV-only)
- Talks to the existing Octi Server (`/v1/...` REST + `/v1/ws`)
- No Google Drive backend in MVP

See [the implementation plan](../../../home/darken/.claude/plans/look-at-https-github-com-d4rken-org-octi-calm-starfish.md) for full design rationale and phasing.

## Build

```bash
./gradlew run                 # run from sources
./gradlew packageDistribution # build OS-native installer (.dmg / .msi / .deb)
./gradlew test                # unit tests
```

## Wire compatibility

This module **copies** wire types from `../app-main/` rather than depending on it directly. Drift is prevented by:

- Golden serialization fixtures (Android + desktop must produce identical JSON)
- Cross-platform Tink encryption tests (Android encrypt ↔ desktop decrypt)
- HTTP request snapshot tests
- E2E sync test against a `sync-server` container

The version catalog values for shared dependencies (Kotlin, kotlinx.serialization, Tink, Ktor, coroutines) must match `../app-main/buildSrc/src/main/java/Versions.kt`. CI fails on drift.
