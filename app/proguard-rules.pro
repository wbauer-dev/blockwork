# R8 rules for the release build. Libraries (Hilt, Room, Compose, kotlinx.serialization,
# Nordic BLE) ship their own consumer rules.

# Garmin FIT SDK: message/field profile tables are looked up by number at runtime; keep it intact.
-keep class com.garmin.fit.** { *; }
-dontwarn com.garmin.fit.**

# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
