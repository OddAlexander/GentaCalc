# CLAUDE.md

## Overview

**GentaCalc** is an Android calculator app built with Kotlin and Jetpack Compose.

- Package: `com.example.gentacalc`
- Min SDK: 24 | Target SDK: 36
- Build system: Gradle with Kotlin DSL (`.kts`)

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Install on connected device
./gradlew installDebug

# Check connected devices
adb devices
```

## Project Structure

```
app/src/main/java/com/example/gentacalc/
  MainActivity.kt        # Entry point
  ui/theme/
    Color.kt
    Theme.kt
    Type.kt
```

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material3
- **Architecture:** Single-module Android app
