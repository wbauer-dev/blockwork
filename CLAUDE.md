# IndoorTrain

Android app for structured indoor cycling: Polar H9 (BLE Heart Rate Service) + Wahoo KICKR Core (FTMS, ERG mode only),
interval workouts, rule-based plan generator, workout import, FIT export. No network access (no INTERNET permission). See [PLAN.md](PLAN.md) for scope and phases.

## Build & test
- `./gradlew test` – all JVM unit tests (domain + ble parsers)
- `./gradlew assembleDebug` / `./gradlew installDebug` – build / install on the USB-connected phone
- Android SDK at `~/Android/Sdk`, Android Studio via snap. Gradle runs on JDK 25; bytecode target is Java 17.

## Modules
- `:domain` – pure Kotlin/JVM (no Android). Workout model, engine, zones, plan generator, importers. Put logic here.
- `:ble` – BLE devices (Nordic BLE library), GATT UUIDs, byte parsers/encoders (pure, unit-tested).
- `:data` – persistence (DataStore, Room with exported schemas + auto-migrations), FIT export (Garmin FIT SDK).
- `:app` – Compose UI, ViewModels, Hilt wiring, foreground TrainingService.

## Conventions
- Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, Hilt (KSP). Versions only in `gradle/libs.versions.toml`.
- AGP 9 uses built-in Kotlin: do **not** apply `org.jetbrains.kotlin.android`.
- Workout targets are stored as FTP fraction (1.0 = 100 % FTP), converted to watts only at ride time.
- Every BLE parser/encoder gets unit tests with raw byte payloads.

## Data protection
Heart-rate data is health data (Art. 9 GDPR). Use only simulated/synthetic data in tests, sample files and prompts —
never real ride recordings or HR exports.
