# Vision

Vision is an offline-first Android assistant for the iQOO Z9x. The first release is a normal APK, designed around explicit user confirmation before every meaningful action.

## Phase 1

- Native Android project shell
- Portrait-first Vision interface
- Offline/model readiness status
- Local command composer
- Recent activity surface

The local model, notifications, voice input, and app adapters will be added in later verified phases.

## Build

Open the project in Android Studio or run it with a compatible Android Gradle Plugin toolchain:

```bash
./gradlew :app:assembleDebug
```

The current Termux environment does not include the Android SDK or Gradle, so APK compilation will be performed when the Android toolchain is available.
