# IndoorTrain

[![CI](https://github.com/wbauer-dev/indoortrain/actions/workflows/ci.yml/badge.svg)](https://github.com/wbauer-dev/indoortrain/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/wbauer-dev/indoortrain)](https://github.com/wbauer-dev/indoortrain/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

An Android app for structured indoor cycling. It controls a smart trainer in **ERG mode**, records a Bluetooth heart rate strap, and builds **rule-based training plans**. It works without an account or cloud, and all data stays on the phone.

**[Install the app →](docs/INSTALL.md)**

## Features

- **Devices:** any Bluetooth heart rate strap (standard Heart Rate Service) and any smart trainer with **FTMS** (Fitness Machine Service). Auto-reconnect with backoff; a dropout during a workout doesn't stop it.
- **ERG workouts:** targets as % of FTP, intervals, ramps and free-ride segments. Controls: pause, skip, +1 min, intensity ±5 %.
- **Training screen:** 3-second power against target, interval countdown, next interval, heart rate (with zones), cadence, and a workout profile with your actual power drawn over it. Works in portrait and landscape.
- **Cues:** beeps, vibration and optional voice announcements ("In 10 seconds: 4 minutes at 280 watts").
- **Keeps running with the screen off:** foreground service with a notification (pause / skip).
- **Workout library:** built-in workouts, an editor, and import of Zwift `.zwo`, `.erg`, `.mrc` and JSON files, including "Open with IndoorTrain".
- **FTP ramp test** with an FTP estimate (75 % of best 1-minute power).
- **Training plans:**
  - Goals: base endurance, FTP build, VO2max, general fitness. Length 4–12 weeks, 2–6 days per week.
  - Blocks of build weeks followed by a recovery week, with an FTP test in week 1 and in every recovery week.
  - Progressive intervals and never two hard days in a row.
  - Plans can be exported and imported as JSON.
- **History:** charts and metrics (NP, IF, TSS, kJ). **FIT export** for manual upload to Strava, Garmin Connect, intervals.icu and others.
- **Simulation mode:** a simulated trainer and heart rate strap for trying the app without hardware.

Tested with a Polar H9 and a Wahoo KICKR Core on Android 17.

## Privacy

- The app has **no internet permission**. Rides, heart rate and settings stay on the phone.
- App data is excluded from Android cloud backup and device transfer.
- Data only leaves the phone when you export a FIT file or share a workout or plan yourself.

## Disclaimer

IndoorTrain is a training tool, **not a medical device**. Heart rate values and zones are for training guidance only. Check with a doctor before starting intense training.

## Building from source

Requirements: JDK 21 or newer and the Android SDK with platform 37. Android Studio provides both.

```bash
./gradlew test             # unit tests (JVM)
./gradlew assembleDebug    # debug APK: app/build/outputs/apk/debug/
./gradlew installDebug     # install on a USB-connected phone
```

Debug builds install as **"IndoorTrain Dev"** (`app.indoortrain.debug`), next to a release install. Without hardware, switch on **Settings → Simulation mode**.

### Project structure

| Module | Contents |
|---|---|
| `:domain` | Pure Kotlin/JVM: workout model and engine, training session, plan generator, file formats (ZWO, ERG/MRC, JSON), statistics |
| `:ble` | Bluetooth LE: heart rate and FTMS trainer (Nordic BLE library), byte parsers |
| `:data` | Room database, DataStore settings, FIT export (Garmin FIT SDK) |
| `:app` | Jetpack Compose UI, Hilt, foreground training service |

Releases are built by GitHub Actions from version tags. See [docs/RELEASING.md](docs/RELEASING.md).

## License

Copyright 2026 wbauer-dev. Licensed under the [Apache License 2.0](LICENSE).

### Third-party components

- **Garmin FIT SDK:** [FIT Protocol License](https://developer.garmin.com/fit/download/)
- **Nordic Android BLE library:** BSD-3-Clause
- **AndroidX, Jetpack Compose, Hilt/Dagger, kotlinx.coroutines, kotlinx.serialization:** Apache 2.0

Zwift, Wahoo, KICKR, Polar, Strava and Garmin are trademarks of their respective owners. This project is not affiliated with or endorsed by any of them.
