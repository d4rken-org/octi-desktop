# Build Commands

## Building

```bash
./gradlew run                  # run from source (Compose Multiplatform application plugin)
./gradlew createDistributable  # produces a runnable image under build/compose/binaries/main/app/Octi
./gradlew test                 # unit + integration tests
./gradlew check                # tests + Kover coverage report (HTML at build/reports/kover/html/index.html)
```

**Note**: `installDist` is NOT a thing here — that's the sync-server's Ktor plugin. Compose Multiplatform uses `createDistributable`.

## Version source of truth

`gradle.properties` `version=X.Y.Z[-rcN|-betaN]`. Read by both `project.version` and the `generateBuildConfig` task that writes `eu/darken/octi/desktop/BuildConfig.kt` at build time. Bumped exclusively by `release-prepare.yml`. See [release.md](release.md) for the full release flow.

`Octi --version` / `Octi -v` prints the version and exits 0 — used by CI's per-OS smoke step to confirm the packaged binary launches before publishing.

## Running the built binary

```bash
./build/compose/binaries/main/app/Octi/bin/Octi
```

Bundled JRE; self-contained. Add CLI flags after the executable:

```bash
./build/compose/binaries/main/app/Octi/bin/Octi --enable-debug-rpc --debug-rpc-port 53123
```

See [debug-rpc.md](debug-rpc.md) for the orchestration endpoint.

## Native packaging

```bash
./gradlew packageDmg           # macOS .dmg  (macOS host only)
./gradlew packageMsi           # Windows .msi (Windows host only)
./gradlew packageDeb           # Linux .deb
./gradlew packageRpm           # Linux .rpm  (needs rpmbuild)
./gradlew packageAppImage      # Linux app-image directory (CI wraps with appimagetool → .AppImage file)
```

Each platform's installer can only be built on a host of that platform — jpackage doesn't cross-compile. The CI matrix in `.github/workflows/release-tag.yml` runs one job per OS. All artifacts are **unsigned** in v1 — Gatekeeper / SmartScreen warnings expected; README documents the workaround.

## JDK

JDK 21 (toolchain enforced via `jvmToolchain(21)`). Older JDKs will fail at configure time.

## Context Management

When running gradle builds or tests, use the Task tool with `devtools:build-runner` to keep verbose output out of the main conversation. Run gradle directly in the main context only when the user explicitly requests full output.
