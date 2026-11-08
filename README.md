# NOX Music

A lightweight **original native Android music player** written in Kotlin, with a monochrome interface.

## 0.2.0 Alpha (On Demand)

- **Audius**: search, trending tracks and on-demand playback of publicly streamable music, without login.
- **Jamendo**: optional additional on-demand catalogue using your own free `client_id` (Settings). The demo client ID from Jamendo's docs is intentionally not embedded.
- **Local library**: play MP3/FLAC/M4A and other Android-supported audio without internet.
- **Library**: favorites, listening history, named playlists and persistent queue, with a SQLite database stored entirely on device.
- **Playback**: Media3 ExoPlayer, background playback, Bluetooth and lock-screen controls; queue editing, shuffle, repeat and seeking.
- **Discovery**: album and artist views when metadata is available, provider labels, artwork and simple genre search.
- **Radio**: retained as an optional secondary feature using Radio Browser.
- **Design**: new monochrome adaptive launcher icon, dark typography, subtle motion and bounded list rendering.
- No ads, account requirement or telemetry.

The Spotify or YouTube Music commercial catalogue is **not included**. Third-party providers may be unavailable or may restrict tracks. Public Audius/Jamendo results are not guaranteed to include any particular artist.

## Android requirements

Android 8.0 (API 26) or later. Target API 36. Tested by CI compilation, not yet field-tested on every phone.

## Build

JDK 17, Gradle 8.13, Android SDK 36:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions workflow produces the **NOX-Music-0.2.0-alpha-debug** build artifact for the `feature/nox-0.2.0` branch. Download the ZIP from Actions → successful run → Artifacts. Debug APKs built on different machines may have different signing keys. If Android rejects an update with a signature error, uninstall the earlier debug build, or configure a persistent signing key for future builds. Back up any personal app data first.

## Sources and privacy

- **Audius API**: https://docs.audius.co/api/
- **Jamendo API**: https://developer.jamendo.com/v3.0/tracks
- **Radio Browser**: https://www.radio-browser.info/
- Music lists and favorites are local to the device. No personal data is sent to NOX servers.
- The app does not scrape Spotify, bypass DRM or use leaked credentials.

## Known limitations

- Audius and Jamendo streaming depends on each API's network availability and rights.
- Jamendo's developer client ID must be supplied before its provider becomes active.
- Large lists are loaded in pages, but still use native Android Views (RecyclerView planned).
- Some artist/album metadata may be missing from third-party providers.
- Changes to the currently playing queue are saved locally; playback does not automatically resume after an app restart.
- Commercial licensing conditions still apply if this project is redistributed for other uses.
