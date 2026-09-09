# WaveQ merge notes

Base: Ishaan's branch (11,493 lines). Merged in selected work from Faraz's
branch (3,666 lines) per the agreed decisions.

**Not compiled.** These files were merged by inspection, not by a build. Expect
a round or two of fixes in Android Studio — see "First build" below.

---

## Decisions applied

| # | Item | Decision | Result |
|---|---|---|---|
| 1 | `CrisisMapView` | **Skip** | `MapPlaceholder` stays on Home and Operator |
| 2 | Evacuation + shelters | **Take (a)** | New `EvacuationMapScreen`, `SafeZone`, `NearestSafeZoneCard` |
| 3 | Charts | **Take (a)** | Real donut / bar / sparkline replace the three `ChartPlaceholder` calls |
| 4 | Incident persistence | **Take (a)** | Room + WorkManager, `ReportDisasterSheet` now actually saves |
| 5 | `OperatorDashboardScreen.kt` | **Skip** | Base branch version is an earlier fork; yours is more current |
| 6 | Faraz's `VoiceRecorder` | **Skip** | Yours is the mesh-wired one |

UI direction: yours throughout. Every merged file was rewired off the raw
light-mode colour vals onto `MaterialTheme.colorScheme` / `appExtraColors`, so
nothing imported breaks dark mode.

---

## New files

- `model/SafeZone.kt` — shelter model, distance helper, demo shelter list
- `ui/components/EvacuationCard.kt` — nearest-shelter card, distance formatting, navigation intent handling
- `ui/screens/EvacuationMapScreen.kt` — OSMDroid map, live GPS, route line, shelter list
- `ui/components/AnalyticsCharts.kt` — `DonutChart`, `HorizontalBarChart`, `SparklineTrendChart`
- `data/local/IncidentEntity.kt`, `IncidentDao.kt`, `WaveQDatabase.kt`
- `data/sync/IncidentSyncWorker.kt`
- `data/IncidentRepository.kt`

## Modified files

- `AppNavigation.kt` — added `Routes.EVACUATION` + drawer entry; rewired the report flow to persist and relay
- `MeshViewModel.kt` — added `broadcastIncidentReport()`
- `OperatorScreens.kt` — three chart placeholders replaced with real charts
- `ReportDisasterSheet.kt` — confirmation dialog rewritten (see below)
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — added OSMDroid 6.1.20 and WorkManager 2.10.0

---

## Changes made during the merge, and why

**Room switched from `annotationProcessor` to KSP.** The base branch declared
`annotationProcessor("androidx.room:room-compiler")`, which does nothing for
Kotlin sources — no DAO implementation is generated. That persistence layer
almost certainly never worked. It now uses the `ksp(...)` setup your branch
already had.

**Two Room databases, deliberately.** `waveq_incidents` (reports, long-lived)
is separate from the existing mesh `MessageStore` (envelopes, expire in hours).
Different lifetimes and eviction rules; merging them would force one policy on
both.

**The confirmation dialog no longer fabricates delivery.** It previously said
"an alert has been broadcast to N users within 5 km" with `N = (20..80).random()`
while nothing was sent anywhere. It now reports the actual reference ID and the
real connected-peer count, and says "handed to N devices", not "delivered".

**Citizen reports relay as `TEXT`, not `FLOOD_ALERT`.** A `FLOOD_ALERT` is an
authoritative warning that fires sirens and is operator-gated in `MeshManager`.
Sending citizen reports as alerts would either be blocked by the role gate or,
worse, let any citizen trigger every nearby siren.

**Evacuation route redraws on GPS fix.** The original drew the route once in the
`factory` block from a hardcoded Delhi start point, so it never pointed at the
user. It's now in `update` and redraws when the fix arrives. All shelters are
drawn as markers, not just the nearest.

**Charts read from the incident list.** They derive from `incidents` rather than
separate constants, so the chart totals can't silently disagree with the list
above them.

---

## First build — expected friction

1. **Sync Gradle.** Two new dependencies.
2. **KSP must process the new entity.** If Room complains about the schema, a
   clean build usually clears it.
3. **Unresolved imports** are the most likely failure. Paste the error into
   Claude Code rather than hand-fixing.
4. **OSMDroid tiles.** Needs `INTERNET` (already present) and the
   `userAgentValue` set, which `EvacuationMapScreen` does in its factory block.
   Blank grey map = one of those two.

Suggested first Claude Code prompt:

```
This project was merged from two branches and has not been compiled since.
Run ./gradlew assembleDebug and fix every compile error. Do not add features
or change behaviour — only make it build. Report what you had to fix.
```

---

## Known gaps carried in

- `IncidentSyncWorker` has **no backend**. It flips `isSynced` and logs. The TODO
  in the file says so; don't present "synced" as "received by authorities".
- Shelter list is a hardcoded demo set for one district (Bijnor). Needs an
  authority feed or a bundled national dataset.
- Evacuation route is a **straight line**, not a road route. In hill terrain the
  direct line may cross a river or a ridge. Needs OSRM/GraphHopper before it can
  be trusted.
- The 24-hour trend chart uses a placeholder series — incidents don't yet carry
  bucketable timestamps. Marked TODO in `OperatorScreens.kt`.
- Everything in `AUDIT.md` that you deferred still applies. The alerts-dead-when-
  app-closed finding (#5) is unaffected by this merge.
