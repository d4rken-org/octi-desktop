# Octi Desktop

Desktop companion for [Octi](https://github.com/d4rken-org/octi) — view your phone's modules and share files between your devices from your laptop.

Talks to the existing [Octi Server](https://github.com/d4rken-org/octi-server) over the same end-to-end-encrypted protocol the Android app uses. No Google account, no separate backend.

## Status

Pre-alpha. Useful enough to link to a real phone and round-trip clipboard, meta, and files. Not yet packaged for end users; build from source for now.

| Capability | State |
|---|---|
| Link to an Octi account (paste linking code from Android) | works |
| Read peer modules (battery, meta, wifi, connectivity, apps, clipboard) | works |
| Write own meta (`DESKTOP` type with `osType` / `osVersionName` from PR [#306](https://github.com/d4rken-org/octi/pull/306)) | works |
| Read/write clipboard sync | works |
| Files: list, download, share, request deletion | works (GCM-SIV accounts only) |
| WebSocket push (`/v1/ws`) | works |
| Native packaging (`.dmg` / `.msi` / `.deb` / `.AppImage`) | not yet wired |

Older Octi accounts created before AES-256-GCM-SIV rolled out can't share files from desktop — Tink's streaming AEAD that blobs use is GCM-SIV-only. The Settings screen shows the keyset type so you can tell which mode an account is on.

## Build

Requires JDK 21. The project is an independent Gradle build (it is not part of the `octi` Android project).

```bash
./gradlew run                 # run from sources
./gradlew createDistributable # build a runnable image under build/compose/binaries/main/app/Octi
./gradlew test                # unit tests
```

`createDistributable` produces a self-contained app image — `bin/Octi` (Linux/macOS) or `bin/Octi.exe` (Windows) — that bundles a JRE. Native installers (`packageDmg` / `packageMsi` / `packageDeb` / `packageAppImage`) will follow once signing is wired up.

## Where it stores data

- **Linux**: `$XDG_CONFIG_HOME/octi` (config + encrypted credentials) and `$XDG_DATA_HOME/octi` (caches)
- **macOS**: `~/Library/Application Support/octi/`
- **Windows**: `%APPDATA%\octi\` and `%LOCALAPPDATA%\octi\`

Credentials are stored in the OS keystore (libsecret / Keychain / DPAPI). If the OS keystore is unavailable (e.g. headless Linux without D-Bus), an Argon2id-derived passphrase fallback kicks in and prompts on every launch.

## Stack

| Concern | Choice |
|---|---|
| UI | Compose Multiplatform 1.7 |
| Language / runtime | Kotlin 2.3 on JDK 21 |
| HTTP / WS | Ktor Client 3.4 (OkHttp engine) |
| Encryption | Tink Java 1.16 with Conscrypt-OpenJDK as the JCE provider (matches `tink-android` byte-for-byte) |
| Serialization | kotlinx.serialization 1.10 with the same custom serializers as `app-main` |
| Secret storage | OS keystore via JNA, Argon2id passphrase fallback |
| DI | Manual `AppGraph` factory (no Hilt on desktop) |

Wire types are **copied** from `app-main` rather than depending on it as a Gradle module — `app-desktop` is a JVM-only build and can't pull in the Android-specific layers. Drift is held back by behavior fixtures (link-code decode, AAD encrypt/decrypt, blob round-trip) rather than rote enumeration.

## Compatibility

| Component | Required version |
|---|---|
| [Octi Server](https://github.com/d4rken-org/octi-server) | latest |
| [Octi Android](https://github.com/d4rken-org/octi) | post-[#306](https://github.com/d4rken-org/octi/pull/306) — older Android builds will fail to decode the desktop's `DESKTOP` meta payload |

## Community

Join the [Octi Discord](https://discord.gg/s7V4C6zuVy).

## License

GPL v3, matching the main Octi project. See [LICENSE](LICENSE).
