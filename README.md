# tide

An unofficial **TIDAL** music tool for the **Light Phone III**, built on the official
[Light SDK](https://github.com/lightphone/light-sdk) and designed to look and feel
like a native LightOS tool: black & white, typography-first, no album art, no feeds,
no noise. Just your music.

```
┌───────────────────────────┐
│           tide            │
│                           │
│  search                   │
│  favorites                │
│  playlists                │
│  albums                   │
│  artists                  │
│  now playing              │
│  settings                 │
│                           │
│  · currently playing song │
└───────────────────────────┘
```

## What it does

- **Link your TIDAL account** with the standard device flow: the phone shows a short
  code, you confirm at `link.tidal.com` on any other device. No password ever touches
  the phone. Requires an active TIDAL subscription.
- **Search** tracks, albums, artists, and playlists using the LP3's on-screen keyboard.
- **Browse your library**: favorite tracks, albums, artists, and your playlists
  (own + followed).
- **Artist pages**: top tracks and full discography.
- **Playback**: play/pause, next/previous, tap-to-seek on a dotted LightOS-style
  progress line, shuffle, repeat (off / all / one), and a live queue you can jump
  around in.
- **Favorites management**: star/unstar tracks from the player, favorite/unfavorite
  artists.
- **Quality selection**: 96 kbps AAC / 320 kbps AAC / FLAC lossless (hi-res tiers
  fall back to the best progressive stream available).
- **Light & dark themes**, matching the LightOS theme system.

## How it's built

This repo follows the Light SDK's intended structure — the SDK scaffolding
(`sdk/`, `plugin/`, `lint-rules/`, MIT-licensed by The Light Phone) is vendored, and
the app lives in the [`tool/`](tool) module as a standard Light tool:

- `tool/src/main/kotlin/com/kezo/tide/api/Tidal.kt` — TIDAL client (Ktor + kotlinx
  serialization): OAuth2 device flow with token refresh, library/catalog endpoints,
  search, favorites, and stream-URL resolution. Speaks the same API the open-source
  ecosystem (e.g. [python-tidal](https://github.com/tamland/python-tidal)) uses.
- `tool/.../player/TidePlayer.kt` — queue + playback engine over
  `android.media.MediaPlayer` (the SDK's allowed-API surface has no media library yet).
- `tool/.../screens/` — `LightScreen`/`LightViewModel` pairs for home/login, search,
  lists, artist, player, queue, and settings, built entirely from the SDK's UI kit
  (`LightText`, `LightTopBar`, `LightLazyScrollView`, `LightTextInputEditor`,
  `LightIcons`, grid units).
- Everything passes the Light SDK plugin's restrictions: no blocked Android APIs,
  no extra third-party dependencies, metadata declared in
  [`tool/lighttool.toml`](tool/lighttool.toml).

## Building

Prerequisites (same as any Light SDK project):

1. JDK 17+, Android SDK with platform 36.
2. A GitHub token with **package read** access, because Light currently hosts its
   keyboard library on GitHub Packages (see the
   [SDK quickstart](https://github.com/lightphone/light-sdk#grabbing-a-token)).
   Put it in `local.properties`:

   ```properties
   sdk.dir=/path/to/android-sdk
   gpr.user=your_github_username
   gpr.key=your_github_token
   ```

3. Build:

   ```sh
   ./gradlew :tool:assembleRelease
   ```

## Running it

- **On a Light Phone III**: `lighttool.toml` ships with `serverPackage = "com.lightos"`.
  Sideload the APK over ADB (`adb install tool/build/outputs/apk/release/tool-release.apk`).
  Once Light's community-tool pipeline is live, the intended path is to have Light
  build and sign the tool from a public git commit of this repo.
- **On the LightOS emulator**: switch `serverPackage` to
  `"com.thelightphone.sdk.emulator"` in `tool/lighttool.toml` and follow the
  [emulator setup docs](https://github.com/lightphone/light-sdk/tree/main/docs/system_app)
  (1080×1240, API 34+, no Google Play).

## Known limitations

- **No background playback yet.** The Light SDK currently blocks services and media
  libraries, so audio plays while the tool is open and pauses when LightOS pauses the
  tool. When Light exposes an audio/background API, `TidePlayer` is the single place
  to swap it in.
- **Hi-res (DASH) streams** aren't parsed; the player steps down to the best
  progressive stream (FLAC lossless works).
- The TIDAL client credentials in `api/Tidal.kt` are the device-flow credentials
  published across open-source TIDAL clients; you can substitute your own from the
  [TIDAL developer portal](https://developer.tidal.com/).

## Legal

This is an unofficial client, not affiliated with or endorsed by TIDAL or Light.
It streams through the account of the signed-in user, who must hold an active TIDAL
subscription. SDK scaffolding © The Light Phone, MIT license (see [LICENSE](LICENSE)).
