# NOX Music

**NOX Music** is an original, lightweight Kotlin music player for Android. No ads, no forced account, no WebView. Designed for small-memory phones.

## 0.4.0 Alpha — YouTube Music catalog access

NOX now offers a dedicated **YouTube Music** tab built around the **real official music.youtube.com website**, keeping the original black-and-white UI for Audius, Jamendo, and local files.

- Search for mainstream songs within YouTube Music's real catalog. You can enter a native search query and choose "PESQUISAR NO YOUTUBE MUSIC" to open the site's search results.
- Use the native NOX toolbar to reload and open the current page in the official YouTube Music app or a regular browser if Android WebView cannot play it.
- YouTube Music playback is **controlled by Google's website**, not by NOX Media3. The app **does not** extract audio, remove ads, download tracks, bypass paywalls or intercept Google account credentials.
- **Important:** This is a *web-integrated* catalog, NOT a native official YouTube Music streaming SDK. YouTube Music's site may refuse embedded browsers on some devices. Use the "ABRIR APP" fallback. Playback while the app/tab is in the background is disabled, in accordance with the service's policies.
- Google login may be blocked inside WebView. Use the official app/browser for Google sign-in; passwords are never entered into NOX-owned forms.
- Media3's local, Audius and Jamendo features continue unchanged.

## 0.3.0 Alpha — Premium Refresh

### Listen
- On-demand tracks from public, accessible Audius streams.
- Optional Jamendo on-demand catalog, **enabled by default** with the owner-supplied public application client ID. Settings can disable Jamendo or override the ID.
- Offline local library (Android MediaStore), artist/album info, MP3/M4A/FLAC and device-supported formats.
- Radio Browser remains available as a secondary option.
- One Media3 session handles the playing queue, background audio, Bluetooth headset buttons and lock-screen controls.

### Experience
- Native Android SplashScreen with monochrome NOX logo; no artificial loading delays.
- New home with continue-listening hero, quick navigation, listening history, Jamendo discoveries, Audius trending, local library and saved playlists.
- Source-aware search, progressive parallel search results and placeholder skeletons.
- New compact mini-player with a thin progress indicator, improved artwork focus in Now Playing.
- Favorites, playlists, editable queue, shuffle/repeat, offline library sort.

### Image fixes and speed
- Audius parser recognizes `_480x480`, `_150x150` and alternate artwork sizes.
- Coil 3 image loader, with 8 MiB RAM cache and 80 MiB disk cache, automatic cancellation, downsampling and crossfades.
- Local album artwork URI lookup (availability varies by Android device and metadata).
- Cache size display and clearing in Settings.
- Bounded track lists and search debouncing; no telemetry.

## Build

Requires Java 17, Gradle 8.13 and Android SDK 36.

```shell
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions workflow runs unit tests, assembles a signed **debug** APK and uploads it under the corresponding run's **Artifacts** section for `feature/nox-0.3.0-premium`. Release signing is **not** configured by default. For **repeatable debug-APK upgrades**, set four repository Actions secrets: `NOX_KEYSTORE_BASE64` (Base64-encoded private Android keystore), `NOX_KEYSTORE_PASSWORD`, `NOX_KEY_ALIAS`, `NOX_KEY_PASSWORD`. The workflow then signs builds consistently using that private keystore; never commit a .jks to a public repository. Android debug signatures can differ between builds; a signature mismatch may require uninstalling the old debug APK (which deletes its local app data). Use a persistent private keystore for upgrades in a production release.

## Privacy & content

Sources: [Audius](https://docs.audius.co/api/), [Jamendo](https://developer.jamendo.com/v3.0/tracks), and [Radio Browser](https://www.radio-browser.info/). Track availability and licensing depend on the providers. No leaked Spotify credentials, DRM bypass, or commercial catalog scraping is used. A Jamendo client ID is a public identifier, **not a private API secret**.

## Notes and limitations

- Compilation and unit tests do not guarantee all devices and networks work; test music playback, startup, cover art and performance on a real phone.
- Jamendo API access with the supplied ID has not been independently verified on-device. In an API failure, Jamendo shows an unavailable state while local playback remains usable.
- Track/album art may not exist in source metadata; the app uses a neutral placeholder.
- Lists are paginated native Views; a full RecyclerView architecture can follow after profiling.

## Release history

- 0.1.0 Alpha: local music and radio
- 0.2.0 Alpha: public on-demand streaming, playlists, history, monochrome theme
- 0.3.0 Alpha: premium refresh, on-disk artwork caching, Jamendo default, improved home and navigation
- 0.4.0 Alpha: official YouTube Music web catalog entry point with safe external fallback

Copyright and licenses remain with their respective owners.
