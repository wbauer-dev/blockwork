# Indoor Cycling App – Project Plan

Status: draft v1 · 2026-11-16 · Phase 0–4 done · Phase 5 implemented, device test pending

## 1. Goal & scope

An Android app for structured indoor cycling. It runs on a phone and controls a Wahoo KICKR Core in ERG mode while recording heart rate from a Polar H9.

**First release: personal use.** A Play Store release may come later. Design choices should not block that, but Play-Store-only work (backend, policies) waits until then.

### Fixed requirements

| Topic | Decision |
|---|---|
| Target device | Android phone, Android 17 (API 37); portrait first |
| HR sensor | Polar H9, using the standard BLE Heart Rate Service (no Polar SDK) |
| Trainer | Wahoo KICKR Core over FTMS, **ERG mode only** |
| Cadence | Taken from the KICKR Core only (no separate sensor) |
| Training plans | Rule-based generator, plus import of workouts and plans |
| Export | FIT export through the share sheet; manual upload to Strava/Garmin/intervals.icu. Strava API dropped (access requires a subscription) – parked on branch `feature/strava-upload` |

### Out of scope (for now)
- Slope/resistance simulation, virtual routes, multiplayer
- ANT+, other trainers or sensors (the architecture still allows adding them)
- Cloud accounts, sync or any network access
- iOS, Wear OS

## 2. Tech stack

- **Kotlin**, **Jetpack Compose** (Material 3), single activity
- **Coroutines + Flow**, MVVM (ViewModel + immutable UI state)
- **Hilt** for dependency injection
- **Room** for persistence; **DataStore** for settings (FTP, max HR, paired devices)
- **Nordic Android BLE library** for the GATT layer
- **Vico** for charts (workout profile, ride graphs)
- **Garmin FIT SDK (Java)** for FIT export
- Gradle with Kotlin DSL and a version catalog. Use the latest stable AGP, Kotlin and Compose BOM when the project is created
- `minSdk 31` (Android 12, which has the modern Bluetooth permissions), `targetSdk`/`compileSdk` = latest stable (37)

## 3. Architecture

```
┌─────────────────────────── :app ────────────────────────────┐
│ Compose UI (screens, ViewModels) · Hilt wiring · Navigation  │
│ TrainingService (foreground, type connectedDevice)           │
└──────────────┬───────────────────────┬──────────────────────┘
               │                       │
┌──────────── :ble ───────────┐ ┌──────── :data ──────────────┐
│ DeviceScanner               │ │ Room DB (workouts, plans,   │
│ HeartRateSensor (0x180D)    │ │   rides, samples)           │
│ FtmsTrainer (0x1826)        │ │ Settings (DataStore)        │
│ Simulated devices (dev/test)│ │ FIT export                  │
└──────────────┬──────────────┘ └──────────────┬──────────────┘
               │                               │
┌──────────────┴───────── :domain (pure Kotlin/JVM) ───────────┐
│ Workout model · WorkoutEngine (state machine) · Zones/FTP    │
│ PlanGenerator (rules) · Importers (.zwo, .erg/.mrc, JSON)    │
│ Interfaces: HeartRateSource, Trainer, RideRecorder           │
└──────────────────────────────────────────────────────────────┘
```

- **:domain** has no Android dependencies. It holds the important logic (engine, generator, parsers), so all of it can be unit-tested quickly on the JVM.
- **:ble** implements the domain's `HeartRateSource` and `Trainer` interfaces. **Simulated** versions of both let us build and test the full training flow without hardware.
- **TrainingService** owns the running session (engine, devices, recorder), so a ride keeps going when the screen turns off or the app goes to the background. The UI only observes its state.

### 3.1 BLE details

**Polar H9 – Heart Rate Service 0x180D**
- Subscribe to notifications on Heart Rate Measurement `0x2A37`. Parse the flags byte: UINT8/UINT16 heart rate, optional energy expended, and RR intervals in 1/1024 s.
- Read Battery Level `0x180F/0x2A19` for display.
- The H9 may not allow more than one BLE connection at a time. A watch or another app that is already connected can block it, so the UI must explain this when connecting fails.

**KICKR Core – Fitness Machine Service 0x1826**
- Data: notifications on Indoor Bike Data `0x2AD2`. Parse the flags field: speed, cadence (0.5 rpm resolution), power, and so on, in the order the flags dictate.
- Capabilities: read Fitness Machine Feature `0x2ACC` and Supported Power Range `0x2AD8`.
- Control: write to the Fitness Machine Control Point `0x2AD9` with indications on.
  1. `0x00` Request Control → wait for response `0x80 00 01`
  2. `0x07` Start/Resume
  3. `0x05` Set Target Power (SINT16, watts) at every target change
  4. `0x08` Stop/Pause at the end
- Status: Fitness Machine Status `0x2ADA` notifications (for example, control lost).
- Fallback, only if needed: the Cycling Power Service `0x1818/0x2A63` for power data, with cadence derived from crank revolution data.
- Before development, update the trainer firmware with the Wahoo app. Close Wahoo, Zwift and similar apps during rides, because they can hold the control connection.

**Connection handling**
- Save paired devices by MAC address and reconnect automatically on app start and after a drop, with backoff.
- During a ride, a dropout must not stop the workout. Show a warning, keep the timer running, and send the current target power again after reconnecting.
- Permissions: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.

### 3.2 Workout engine

- **Model:** a `Workout` is a list of `Segment`s: steady, ramp, or repeat block (N × [on, off]). Targets are stored as **% of FTP** and turned into watts at ride time, so changing the FTP rescales every workout.
- **Engine:** a 1 Hz tick-based state machine with the states `Idle → Countdown → Running ⇄ Paused → Finished`.
  - Each tick it emits the current segment, time left in the segment, the next segment(s), the target watts, and overall progress.
  - On a segment change it sends the new target to the trainer. Ramps send a new target every few seconds.
  - User controls: pause/resume, skip segment, intensity ±5 % (applies to all following targets), extend the current segment.
- **Recorder:** a 1 Hz sample (time, power, cadence, speed, heart rate, target power) saved to Room. A lap marker is written per segment.

### 3.3 Training screen (core UX)

Portrait layout, readable from about 1 m away on the handlebar:
- **Top:** current power (3 s average) shown large next to the **target power**, colour-coded (under, on, or over target)
- **Middle:** heart rate (with zone colour), cadence, segment time left (large), total time elapsed/remaining
- **Upcoming:** a card for the next interval ("Next: 4:00 @ 280 W") with a countdown
- **Bottom:** workout profile chart with a progress marker and the actual power line drawn over it
- **Cues:** beep and vibration 3-2-1 before each segment change; optional spoken cue ("Next: 4 minutes at 280 watts")
- Screen stays on during the ride; notification controls (pause/skip) come from the foreground service

### 3.4 Workout import

| Format | Notes |
|---|---|
| `.zwo` (Zwift XML) | SteadyState, Warmup/Cooldown (ramps), IntervalsT, Ramp, FreeRide (shown as "free", ERG off) |
| `.erg` / `.mrc` | Absolute watts (`.erg`) or % FTP (`.mrc`) |
| JSON (own format) | Full workouts **and whole plans** (weeks → days → workout references); also used for export/backup |

Import works through the Android file picker and "Open with / Share to app". Imported workouts go into the workout library.

### 3.5 Rule-based plan generator

**Inputs:** FTP, goal, plan length (4–12 weeks), sessions per week, typical session length, preferred days.

**Goals (first set):** *Base endurance*, *FTP build*, *VO2max*, *General fitness*.

**Rules:**
- Zones: Coggan power zones (Z1 < 55 %, Z2 56–75 %, Z3 76–90 %, Z4 91–105 %, Z5 106–120 %, Z6 > 120 % FTP)
- Periodization in 3:1 blocks (3 build weeks, 1 recovery week at about 60 % volume)
- Goal-specific mix of session types, for example FTP build = 2 × threshold/sweet spot + 1 × endurance (+ 1 × VO2 in later blocks)
- Progression: each build week makes the time-in-zone harder in a fixed order (more reps → longer reps → higher % FTP)
- No two hard sessions on consecutive days
- An FTP test (ramp test; FTP = 75 % of the best 1-minute power) in week 1 and after each block. The test result can update the FTP after the user confirms it

**Implementation:** a library of parameterized workout templates (for example `threshold(reps, repMin, pct, restMin)`). The generator picks templates and parameters from the rules. Everything is deterministic and unit-tested.

**Plan view:** a calendar or week list with "today's workout". Completed rides are linked to their planned session.

### 3.6 History & export

- Ride list and detail view: power/target and heart-rate charts, averages, normalized power, IF, TSS, average and max HR
- **FIT export:** FileId, Activity, Session (sport cycling, sub-sport indoor cycling), one Lap per segment, Record messages at 1 Hz, shared through the Android share sheet
- Upload to Strava, Garmin Connect or intervals.icu is done manually with the exported file
- **Strava API: dropped** (API access requires a subscription). The implementation (OAuth, Keystore-encrypted tokens, upload with polling) is parked on branch `feature/strava-upload`
- The app needs no network access

## 4. Phases & milestones

### Phase 0 – Setup (½ day)
- Install Android Studio, set up the phone for USB debugging, `git init`
- Use nRF Connect to check the **actual** services on the H9 and KICKR Core (does FTMS exist? Does Indoor Bike Data contain cadence?) and record the results in `docs/devices.md`
- Create the project skeleton: modules `:app`, `:domain`, `:ble`, `:data`, version catalog, Hilt, an empty Compose app on the phone

**Done when:** the app installs on the phone and the device facts are written down.

### Phase 1 – Devices & live data
- Scan/pair screen for both devices, saved pairing, auto-reconnect
- Parse HR Measurement and Indoor Bike Data
- "Live" dashboard: heart rate, power, cadence, speed, connection status, battery
- Simulated HR and trainer devices (switchable in a debug menu)

**Done when:** while pedalling, live heart rate, power and cadence show reliably for 30+ minutes.

### Phase 2 – ERG workouts (MVP)
- Domain: workout model, engine, unit tests
- FTMS control: request control, start, set target power, stop
- TrainingService (foreground), recorder
- Training screen as in §3.3, with a few built-in workouts (for example 4×4 min, sweet spot 3×10, endurance 60 min)
- FTP setting

**Done when:** a complete interval workout runs in ERG mode with the screen off or the app in the background, with cues and saved samples. **← First usable version.**

### Phase 3 – Library & import
- Workout library (list, detail with profile chart, delete)
- Import `.zwo`, `.erg`/`.mrc` and JSON; parser unit tests with sample files
- Simple workout editor (add, edit and reorder segments)
- Ramp FTP test workout with result evaluation
- Note: whole-plan JSON import moved to Phase 5 (together with the plan model); Phase 3 imports single workouts. "Open with / Share to app" is not done yet; import works through the file picker.

### Phase 4 – History & export
- Ride history and ride detail with metrics and charts
- Recovery of rides interrupted by an app kill
- FIT export + share sheet (manual upload to Strava, Garmin Connect, intervals.icu)
- ~~Strava OAuth + upload~~ – dropped: Strava API access requires a subscription. The finished implementation is parked on branch `feature/strava-upload`.

### Phase 5 – Plan generator
- Template library, rule engine, generator unit tests
- Plan creation wizard, calendar/week view, "today's workout", linking rides to planned sessions
- Plan import/export (JSON)

### Phase 6 – Polish / Play Store prep (when needed)
- Onboarding, landscape layout, settings (zones, cues, units), dark theme fine-tuning
- Play Store: privacy policy, Data Safety form (heart rate counts as **health data**), signing, crash reporting without personal data

## 5. Testing strategy

- **Unit tests (JVM):** engine timing and transitions, zone calculation, plan generator rules, all importers, BLE byte parsers (with captured sample packets), FIT writer
- **Simulated devices:** a full training session without hardware, used in instrumented tests and during development
- **Manual device test checklist** per phase: real H9 + KICKR, screen off, connection drop (switch the trainer off and on), a 60-minute ride

## 6. Data protection

- All ride and heart-rate data stays on the phone (the app has no network access). It only leaves the phone as a FIT file the user explicitly exports.
- Heart-rate data is health data (Art. 9 GDPR). During development, use **simulated or synthetic data only** for tests, sample files and AI-assisted work, and never paste real ride recordings or HR exports into prompts.

## 7. Risks & open points

| Risk | Mitigation |
|---|---|
| KICKR Core firmware without FTMS, or no cadence in Indoor Bike Data | Check in Phase 0; fallback to CPS or the Wahoo proprietary protocol |
| H9 blocked by another connection (watch/app) | Clear error message, note in onboarding |
| Android background/BLE restrictions on Android 17 | Foreground service from Phase 2; test the screen-off case early |
| ERG at low cadence (spiral of death) | Ramp the target smoothly; "ERG off/pause" button |
