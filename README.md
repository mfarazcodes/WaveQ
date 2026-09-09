# WaveQ 🌊🚨

**WaveQ** is an Android disaster-response app that keeps working when the network doesn't. Built with Kotlin and Jetpack Compose, it combines citizen incident reporting, an operator command center, a rule-based flash-flood risk engine, and a **phone-to-phone offline mesh** (via Google Nearby Connections) so reports, SOS beacons, and critical alerts can still move between devices when cell towers and Wi-Fi are down.

> **Status: active development, not yet a verified build.** This is a merged snapshot of two branches (see [`MERGE_NOTES.md`](./MERGE_NOTES.md)) and has not been compiled since the merge. A full security and correctness pass lives in [`AUDIT.md`](./AUDIT.md) / [`AUDIT2.md`](./AUDIT2.md) — read those before treating any life-safety path here as trustworthy. See [Known limitations](#known-limitations-read-before-relying-on-this) below for the short version.

---

## Why WaveQ

Most incident-reporting apps assume you have a working connection to a server. WaveQ is built around the opposite assumption: in a flood, earthquake, or hurricane, infrastructure is often the first thing to go. So alongside the normal online reporting flow, WaveQ maintains a **local mesh network** over Bluetooth/Wi-Fi Direct that lets nearby phones relay SOS beacons, chat messages, and flood alerts device-to-device — no internet, no cell signal, no server required.

## Core features

### 📍 Citizen incident reporting
- GPS-based incident reports (Fused Location Provider) with reverse-geocoded address resolution.
- Structured by hazard type (Flood, Fire, Hurricane, Earthquake) and severity (Low → Critical → Evacuate).
- Reports are persisted locally with **Room** and flushed to the backend by a **WorkManager** job once connectivity returns — reporting doesn't require being online at the moment of submission.

### 🆘 Offline mesh network
- Peer-to-peer device discovery and relay over **Google Nearby Connections**, with no internet dependency.
- **SOS beacons**: broadcast your live location to nearby devices, relayed hop-by-hop across the mesh (multi-hop, TTL and hop-limited).
- **Encrypted family/private channels** (AES-GCM, PBKDF2-derived keys) alongside an unencrypted public "City-Wide" channel for open alerts.
- Store-and-forward relay so messages reach peers that connect later, with a bounded local store and expiry.
- Voice notes, text chat, and machine-generated risk updates all travel over the same mesh transport.
- Critical/SOS alerts can trigger a full-screen takeover activity with siren audio, distinct from normal notifications, so an emergency isn't missed with the phone locked or the app backgrounded.

### 🌧️ Flash-flood risk engine
- A **rule-based, physically-grounded** risk model (explicitly *not* a trained ML model) that scores flash-flood risk from rainfall forecasts and river discharge data pulled from Open-Meteo.
- Every score traces back to named, inspectable indicators and documented weights rather than an opaque prediction — the UI is upfront that the weights are reasoned, not yet calibrated against historical events.
- Works offline using the last cached assessment, with its age and provenance (computed locally vs. relayed across the mesh) surfaced to the user rather than hidden.
- Risk updates can be shared over the mesh, clearly attributed to "WaveQ risk engine" rather than a person, so an automated assessment is never mistaken for something a human typed.

### 🗺️ Evacuation & shelters
- Live-GPS evacuation map (OpenStreetMap tiles via OSMDroid — no API key or billing) showing the route to the nearest safe zone.
- Shelter list with distance-sorted safe zones (currently a bundled demo dataset for one district; see [Known limitations](#known-limitations-read-before-relying-on-this)).

### 🧑‍💻 Operator command center
- Crisis map placeholder (real map integration pending — see below) plus live metrics: total incidents, verified reports, pending reviews, average response time.
- Custom Jetpack Compose `Canvas` analytics: donut charts (severity/status breakdown), horizontal bar charts (incidents by hazard type), and a 24-hour sparkline trend — all derived from the live incident list rather than separate hardcoded numbers.
- Broadcast alert flow for pushing authoritative flood/hazard alerts to the mesh's public channel, gated to Operator/Admin roles.

### 🔐 Roles, admin & session
- Three-tier role model — **Citizen**, **Operator**, **Admin** — enforced both in the UI and in the mesh send path (not just cosmetically).
- Admin panel for team member lifecycle (add/inspect/remove) and system health counters.
- Adaptive Material 3 theming (light/dark) tuned for high-contrast, low-light emergency visibility, plus a right-hand modal navigation drawer for one-handed use.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3, custom `Canvas` charts |
| Navigation | Navigation Compose |
| Offline mesh | Google Nearby Connections (`play-services-nearby`) |
| Location | Google Play Services Fused Location Provider, Android Geocoder |
| Local persistence | Room (incidents, prediction cache), Jetpack DataStore (settings/session) |
| Background work | WorkManager (incident sync) |
| Networking | Retrofit + OkHttp (Open-Meteo forecast/flood APIs) |
| Maps | OSMDroid (OpenStreetMap tiles) |
| Security | `androidx.security-crypto` (EncryptedSharedPreferences), AES-GCM + PBKDF2 for mesh channel encryption |
| Concurrency | Kotlin Coroutines |

**Build:** Gradle (Kotlin DSL), Android Gradle Plugin 9.3.2, `compileSdk`/`targetSdk` 37, `minSdk` 26, KSP for Room codegen.

---

## Project structure

```text
app/src/main/java/com/waveq/app/
├── MainActivity.kt              # Entry point, theme init
├── WaveQApplication.kt
├── navigation/
│   └── AppNavigation.kt         # NavHost, routes, role-gated navigation, drawer
├── auth/                        # Session + role model (Citizen/Operator/Admin)
├── data/                        # Incident repository, Room entities/DAO, mesh sync bridge
│   ├── local/                   # IncidentEntity, IncidentDao, WaveQDatabase
│   └── sync/                    # IncidentSyncWorker (WorkManager)
├── mesh/                        # Offline P2P mesh: transport, crypto, channels, SOS
│   ├── MeshManager.kt           # Core relay/decrypt/dispatch logic
│   ├── NearbyTransport.kt       # Google Nearby Connections wrapper
│   ├── ChannelCrypto.kt         # AES-GCM + PBKDF2 channel encryption
│   ├── SosBeaconService.kt      # Foreground service broadcasting SOS location
│   └── ...                      # Serialization, message store, voice notes, etc.
├── prediction/                  # Flash-flood risk engine
│   ├── RiskEngine.kt            # Rule-based scoring from indicators
│   ├── DataSources.kt           # Open-Meteo forecast/flood clients + cache
│   └── ...
├── alerts/                      # Critical alert dispatch: siren, full-screen intent, notifications
├── location/                    # Location controller, place search/reference
├── settings/                    # Theme, display name, mesh onboarding (DataStore-backed)
└── ui/
    ├── components/               # Charts, evacuation card, shared widgets
    ├── screens/                  # Home, Report, Operator, SOS, Mesh chat/channels, Evacuation, Settings, ...
    └── theme/                    # Material 3 color scheme, typography
```

---

## Getting started

1. Clone the repo and open it in Android Studio (a recent version supporting AGP 9.3 / compileSdk 37).
2. Let Gradle sync — this pulls in Room, WorkManager, OSMDroid, Retrofit/OkHttp, and Play Services Nearby/Location.
3. Run `./gradlew assembleDebug`.
4. Grant the runtime permissions the app requests on first launch: location (fine + coarse), nearby Wi-Fi devices, Bluetooth scan/advertise/connect, microphone (for voice notes), and notifications.

> This snapshot has not been rebuilt since the last merge — expect to fix a compile error or two on first sync. If you're using an AI coding assistant to get it building, point it at `MERGE_NOTES.md` first; it documents exactly what changed and what's expected to need fixing.

No API keys are required to run the app: OSMDroid uses free OpenStreetMap tiles and Open-Meteo's forecast/flood APIs are free and keyless.

---

## Known limitations (read before relying on this)

WaveQ is a work in progress, and because it's a life-safety-adjacent app, it's worth being explicit about what isn't solid yet rather than letting the feature list overstate it:

- **The mesh is unauthenticated by design.** Any nearby device can join and relay traffic; SOS beacons and public alerts are unsigned. This is a deliberate infrastructure-free tradeoff, not an oversight, but it means the mesh is not currently hardened against a malicious peer. See `AUDIT.md` findings on beacon spoofing and unbounded relay.
- **`IncidentSyncWorker` has no backend yet.** It marks reports as "synced" locally, but nothing is actually received by an authority on the other end.
- **The evacuation route is a straight line**, not a routed path — it can currently cross terrain a real road route wouldn't.
- **The shelter list is a hardcoded demo dataset** for a single district, not a live or national feed.
- **The Crisis Map on Home/Operator is a placeholder**, not yet a real interactive map.
- **Voice notes can be recorded and sent but not played back** — the feature is currently incomplete end-to-end.
- A full list of open findings, from critical to low severity, with file/line references and suggested fixes, is tracked in `AUDIT.md` and `AUDIT2.md`. Several open design decisions (e.g. whether SOS should survive process death, whether to sign mesh beacons) are called out explicitly rather than silently resolved.

## Roadmap / open design decisions

- Replace the map placeholder with a real interactive crisis map.
- Add authenticated/signed mesh beacons to close the spoofing gap, if that tradeoff is accepted against the "works with zero infrastructure" goal.
- Wire incident sync to a real backend.
- Replace the demo shelter list with a live or bundled national dataset, and route evacuation paths via a real routing engine (OSRM/GraphHopper) instead of a straight line.
- Calibrate the flash-flood risk engine's weights against historical events.
- Finish or remove voice-note playback.

---

## Contributing

This is currently developed by a small team. See `MERGE_NOTES.md` for the most recent history of what changed and why, and `AUDIT.md` / `AUDIT2.md` before touching the mesh, alerts, or SOS code paths — several of the fixes there are life-safety relevant.
