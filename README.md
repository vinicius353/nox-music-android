# NOX Music

Android music player, written in Kotlin, with a black/white/gray theme and an original NOX UI.

## Version 0.5.0 Alpha — Native InnerTune integration

**NOX now reuses the GPL-3.0 InnerTune `innertube` module** (pinned from [z-huang/InnerTune](https://github.com/z-huang/InnerTune)) to add an unofficial YouTube Music provider to the **native Media3 player**.

- Search songs by title, artist or genre in the native **YouTube Music** tab.
- Search multiple sources from **Buscar**; YouTube Music tracks are prioritized ahead of Audius and Jamendo.
- Tap a result to play it in the NOX mini-player/full-screen player; the Android MediaSessionService supports background playback and screen-off controls.
- Add YouTube Music tracks to favorites, custom playlists and the native editable queue. Tracks persist as video IDs; expired stream URLs are resolved just before playback, not saved to the database.
- Browse artist/album matches, change tabs without pausing audio, and restore a saved queue after reopening the app.
- **Configurações → Ver créditos do InnerTune** displays acknowledgements, links to the upstream InnerTune and ViMusic projects, and GPL-3.0.
- Previous native local music, Audius, Jamendo and radio features remain available, along with the image cache and dark design.

### Important limitations

This integration is **unofficial** and **not endorsed or authorized by YouTube or Google**. It uses code from an open-source project, not an official YouTube Music streaming API. The service can change its endpoints, block requests or restrict particular tracks. Background playback is subject to YouTube's terms and may not be permitted in all circumstances. Do not claim a working stream based only on compilation; field-test on a phone. The app does not support logging into Google or bypassing account/age restrictions.

The old embedded YouTube Music WebView has been removed from the playback path. Direct stream URLs expire and are only used by Media3 for playback, never stored as shareable downloads.

### Build

JDK 17, Android SDK 36, Gradle 8.13. Install the pinned upstream InnerTune source before building:

```sh
bash scripts/fetch-innertube.sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The fetch script pins upstream commit `bfba5ecb16795cfc93ecce73d94ca583bcec917c`, verifies the checkout and copies only the `innertube` module sources and license under `third_party/innertube`. GitHub Actions runs this automatically. No upstream build scripts are executed. Without fetching the source first, the Gradle source module is intentionally incomplete.

The workflow uploads a signed **debug APK** under Actions → successful build → Artifacts. APK signatures may differ between CI runners. To keep signing consistent, configure the secret-backed signing options described in the workflow. Upgrading a differently signed debug build may require uninstalling the old version, which can erase locally stored playlists and preferences.

### Network smoke test

The workflow attempts a best-effort, non-blocking live API smoke test on the native InnerTune module. CI network errors or YouTube service changes can make this check fail. A smoke test does not replace actual testing of streaming and background playback on Android.

## Credits / open-source licensing

- **InnerTune:** [z-huang/InnerTune](https://github.com/z-huang/InnerTune), contributors — `innertube` integration library — **GNU GPL v3**.
- **ViMusic:** [vfsfitvnm/ViMusic](https://github.com/vfsfitvnm/ViMusic), credited upstream by InnerTune.
- **NOX Music:** original Android app and integration glue code; source provided under **GNU GPL v3**.

See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The complete source of modified and combined GPL-covered code must remain available under compatible GPL terms when distributing APKs.

## Previous releases

- 0.1.0 Alpha — Offline library and radio.
- 0.2.0 Alpha — Audius and Jamendo, playlists, history, monochrome UI.
- 0.3.0 Alpha — New home, artwork caching and premium design.
- 0.4.0 Alpha — YouTube Music website (WebView, superseded).
- 0.5.0 Alpha — Native InnerTune-backed song search and Media3 playback.
