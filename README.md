# NOX Music

**NOX Music** is a lightweight, native Android music player written from scratch in Kotlin.

### 0.1.0 Alpha

- Play local audio files using Android MediaStore.
- Browse and play free live internet radio stations using the open Radio Browser directory.
- Unified queue, media controls, lock-screen and Bluetooth playback with Media3.
- Favorites saved on-device, dark UI, animated navigation and custom adaptive icon.
- No account, ads, telemetry or subscription required.

**Important:** Live radio is not an on-demand Spotify/YouTube music catalog. A station chooses what it plays. Some third-party stations may be offline or use incompatible streaming formats.

### Build

Open in Android Studio (Android SDK 36), or use Gradle 8.13 with JDK 17:

```bash
gradle :app:assembleDebug
```

The GitHub Actions workflow builds and uploads a **debug-signed APK** at **Actions → Android APK → latest successful run → Artifacts**. Debug signatures generated on different runners may differ; keeping app data across builds requires a persistent signing key stored securely.

### Permissions

Android audio library access is requested at runtime. Internet is used only for fetching radio directory results and playing stations. Cleartext traffic is enabled because some radio stations still publish HTTP streams.

### License

Source code is original to this repository. See LICENSE when added.

### Roadmap

Improve art thumbnails, playlists, streaming reliability, search, personalization and performance profiling on low-RAM phones.
