# Device Capabilities

Desktop publishes and consumes per-peer feature capabilities via the
`Octi-Device-Capabilities` HTTP header (and the matching field on the device-list response).
Port of `app-main`'s capability system — every limit and tag-format rule **must stay
byte-stable** with upstream Android and the server, or peers misinterpret our tag set.

## Wire contract (cross-platform)

The contract is shared by **all four** implementations (Android `app-main`, this desktop port,
`octi-web`, `sync-server`). Drift here breaks interop.

### Tag format

- `<namespace>:<value>` — ASCII, lowercase namespace.
- Regex: `[a-z][a-z0-9]*:[A-Za-z0-9._\-]+` (enforced by [`CapabilitiesCodec.TAG_REGEX`]).
- Marker convention: `<namespace>:_reported` — emitted by any producer that participates in a
  namespace. Distinguishes "I don't speak this namespace" (no marker) from "I speak it and
  explicitly don't support this value" (marker present, value absent).

### Limits (enforced by `CapabilitiesCodec`)

| Limit | Constant | Value |
|---|---|---|
| Max tags per device | `MAX_TAGS` | 64 |
| Max length per tag | `MAX_TAG_LENGTH` | 128 |
| Max header byte length | `MAX_HEADER_LENGTH` | 4096 |

**Validation on encode AND decode.** Encode throws on bad input (local producer drift = bug);
decode returns null + WARN log (untrusted input). On any bad tag the whole set is rejected.

### Wire transport

- **Outbound**: `Octi-Device-Capabilities` HTTP header on every authenticated REST request and
  on the WebSocket upgrade. Value is a canonical-sorted JSON-stringified `List<String>`.
- **Inbound**: `capabilities` field on each device in the `GET /v1/devices` response. Each
  device's value is decoded via the codec; one malformed device's value yields `null` for
  that device only.

### Authority semantics

For a peer's `capabilities` field, with namespace `X` and value tag `<X>:V`:

| State | Verdict |
|---|---|
| `capabilities == null` | Unknown — caller falls back to platform heuristics or skips |
| `capabilities` non-null, `<X>:_reported` absent | Namespace `X` unknown for this peer |
| `capabilities` non-null, `<X>:_reported` present, `<X>:V` absent | Peer explicitly does NOT support `V` |
| `capabilities` non-null, `<X>:_reported` present, `<X>:V` present | Peer explicitly DOES support `V` |

## Where the code lives

| File | Role |
|---|---|
| `desktop/protocol/sync/Capability.kt` | Tag registry + `supports<X>()` semantic helpers. Port of `app-main/sync-core/Capability.kt`. |
| `desktop/protocol/sync/CapabilitiesCodec.kt` | Shared encode/decode + validation. Single source of truth for tag-format hygiene. |
| `desktop/modules/meta/DeviceCapabilitiesProvider.kt` | Computes the **local** desktop's tag set — what this device declares it supports. |
| `desktop/protocol/octiserver/DeviceMetadata.kt` | `capabilities: String?` field on the per-request metadata struct; `HEADER_CAPABILITIES` constant. |
| `desktop/protocol/octiserver/OctiServerHttpClient.kt` | Sends the header on every authenticated REST request (and WS upgrade — verify when looking at WsClient). |
| `desktop/protocol/octiserver/dto/Endpoints.kt` | `DevicesResponse.Device.capabilities: Set<String>? = null` — inbound DTO field |

Tests under `src/test/.../desktop/protocol/sync/`:
- `CapabilityTest` — authority semantics
- `CapabilitiesCodecTest` — encode/decode + validation

## What desktop declares today

`DeviceCapabilitiesProvider.current()` emits:

```kotlin
add(Capability.ENCRYPTION_NAMESPACE_REPORTED)
add(Capability.encryption(EncryptionMode.AES256_SIV))
add(Capability.encryption(EncryptionMode.AES256_GCM_SIV))
```

Both module-document encryption modes are advertised. Notably **not** advertised today: a
hypothetical `blob:share-v1` capability for the streaming blob pathway. `StreamingPayloadCipher`
is GCM-SIV-only by Tink design (same as Android), so the "no streaming for legacy accounts"
limitation is currently surfaced separately in the UI rather than via capability tags. If/when
the cross-platform schema gains a `blob:*` namespace, declare it here too.

## Adding a new capability namespace

Use `encryption:*` as the worked example. To add namespace `X` with value type `T`:

1. **`Capability.kt`**:
   - `const val X_NAMESPACE_REPORTED = "x:_reported"`.
   - `fun x(value: T): String = "x:${value.toWireString()}"`.
   - `fun supportsX(caps: Set<String>?, value: T): Boolean?` following the encryption pattern.

2. **`DeviceCapabilitiesProvider.kt`**:
   - In `buildSet { ... }`, `add(Capability.X_NAMESPACE_REPORTED)` plus the value tags this
     desktop build actually supports.

3. **Consumer site (if any)** — read `peer.capabilities` from `DevicesResponse.Device` and
   query via `Capability.supportsX(...)`. Today desktop has no encryption-incompat consumer
   equivalent to Android's `OctiServerEncryptionIssues`; if one is added, mirror the
   `PeerSupport` sealed-result pattern from upstream.

4. **Tests**: extend `CapabilityTest` with the new authority cases. Add a `CapabilitiesCodec`
   case if the new namespace stresses the regex (it shouldn't — same shape).

5. **Cross-repo coordination**: bump the matching upstream `app-main/.../Capability.kt`, the
   web declaration in `octi-web/src/protocol/octi-api.ts`, and any consumer that should act on
   the new tags. The server is dumb pipe — no server change needed.

## Stay byte-stable with upstream

This is a **port**, not an independent reimplementation. When upstream changes anything
load-bearing (regex, limits, marker convention, encoding order), this repo must follow on the
next release or peer interop silently breaks.

Specifically:

- `CapabilitiesCodec.TAG_REGEX` must equal `app-main/sync-core/CapabilitiesCodec.kt`'s regex
  byte-for-byte.
- `MAX_TAGS` / `MAX_TAG_LENGTH` / `MAX_HEADER_LENGTH` constants must match upstream.
- `encodeToHeader` must produce a canonical-sorted array — peers compare by string equality
  (and the GDrive hash cache uses byte equality).

If you need to deviate, that's a cross-platform protocol change — coordinate via the upstream
`Capability.kt` + a sync-server validation update.

## Cross-references

- **Android** (worked example): `app-main/.claude/rules/device-capabilities.md`.
- **Web** (TypeScript declaration): `octi-web/.claude/rules/device-capabilities.md` and
  `OCTI_WEB_CAPABILITIES` in `src/protocol/octi-api.ts`.
- **Server** (parse + echo): `sync-server/.claude/rules/device-capabilities.md` and
  `parseCapabilitiesHeader` in `HttpExtensions.kt`.

Sister PRs: [octi#309](https://github.com/d4rken-org/octi/pull/309),
[octi-server#23](https://github.com/d4rken-org/octi-server/pull/23).
