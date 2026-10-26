# Implementation Notes

This file records the current launcher behavior and the implementation decisions made while adapting the personal Android TV build for reuse. Source paths below are relative to `launcher/app/src/main/` unless stated otherwise.

## Home and app navigation

- `java/com/vdx/kinodock/MainActivity.java` owns the Home screen, D-pad navigation, focus states, top controls, timer menu, weather, and Home data rotation.
- The favorites dock is configurable and the rest of the installed apps remain available in the app library. App order and selected favorites are user preferences, not a fixed system list.
- The library supports remote navigation and returning to Home through its back controls.
- `LauncherSettingsActivity.java` owns launcher preferences, wallpaper selection, app ordering, and manual quote refresh.
- Visual app marks are local vector resources under `res/drawable/`. Service marks and names belong to their respective owners; see `THIRD_PARTY_NOTICES.md`.

## Home information cards

`MainActivity` rotates the Home card types on a 30-second timer. The selected quote stays the same when the Quote card returns; a successful quote API refresh replaces it every hour. With Quote, Latest News, and On This Day present, each type returns about every 90 seconds.

| Source | Fetch cadence | Data kept on device | Failure behavior |
| --- | --- | --- | --- |
| ZenQuotes batch endpoint | One hour | Current quote and fetched time in SharedPreferences | Keep the current quote; retry later after failure |
| Currents latest news | 30 minutes; requests up to 20 headlines | Headlines, links, source labels, and timestamps in SharedPreferences | Keep last saved headlines; failed attempts are throttled |
| SlashYear On This Day | Once per local date | Up to 30 selected events, newest year first | Keep last cached events |
| Open-Meteo weather/AQI | Existing weather refresh schedule | Current result in SharedPreferences | Keep last successful result and hide unavailable values safely |

Headlines are D-pad selectable and open their article URL. Quote and history cards do not launch links. The previous one-headline news cache is upgraded by requesting the multi-headline list once; subsequent requests follow the 30-minute schedule. Duplicate titles are dropped before storing the list.

## Wallpaper behavior

- `UnsplashWallpaperApi.java` chooses one randomized dark search phrase per wallpaper update, including landscape, architecture, city, space, and superhero themes.
- A single Unsplash search request is made per update, and a random result is selected while avoiding the previous image where possible. Mountain-tagged results are filtered.
- Unsplash updates every six hours. Manual “Update from Unsplash” disables local wallpaper rotation when it succeeds.
- Wallpaper settings can enable or disable local auto-rotation. When enabled, the local collection rotates every 30 minutes. Selecting Unsplash or one fixed local image turns that mode off.
- The returned image URL is streamed by `WallpaperPlayer.java`; the launcher does not keep a permanent downloaded wallpaper file. The wallpaper picker preview is a bounded in-memory bitmap.
- Locally installed wallpaper files live under the app's `wallpapers/` theme directory. `captions.txt` keeps photographer credits with local images.
- Network errors leave the existing wallpaper in place. Request and decode failures are isolated from the Home UI.

## Appearance and device status

- Home uses a dark background and a lower scrim to preserve app-mark contrast. Focus treatment uses bounded scale/alpha changes; optional advanced effects and ambient glow are configurable.
- Weather shows the resolved city under the temperature. AQI is displayed alongside weather where data is available.
- A small top-right resource line shows RAM used/total in GB and free storage. Some TV kernels report an invalid `MemAvailable`; the code rejects impossible system values and falls back to other `/proc/meminfo` counters.
- On This Day year labels omit the “CE” suffix.

## API and secret handling

- User-specific API values belong only in `launcher/local.properties`, which is Git-ignored.
- `app/build.gradle` uses empty API fields by default. A local debug build may read the contributor's own Unsplash and Currents values; the release build explicitly overrides both to empty strings.
- Android APKs are inspectable. A developer's debug APK may contain that developer's own keys and must not be distributed. Do not commit generated APKs, build output, signing keys, or `local.properties`.
- API calls use connect/read timeouts and run off the UI thread. Cached data is displayed when a request fails; no API error should take down the launcher.
- All app HTTP clients use a Chrome-style User-Agent. Keep provider-specific policy requirements in mind if replacing or expanding an API integration.

## Build and install

The launcher is branded **KinoDock**, with package ID `com.vdx.kinodock`. The personal TV installation was migrated to this package, including its preferences and local theme/wallpaper collection; the former `com.gathin.tvhome` package was removed after the migration was verified. The public source does not include the personal theme or wallpaper files. See [README.md](README.md) for build and install commands.

## Remaining open-source tasks

- `screenshots/home.png` is a clean Home preview with no personal wallpaper. The personal App Library contains locally installed apps and is not included in the public screenshots.
- Before public distribution, review app/service marks and wallpaper assets for redistribution rights. Local `config.json` and the local `wallpapers/` folder are ignored by the root `.gitignore`.
- Decide how release builds should receive Currents and Unsplash access without embedding shared secrets. Current release builds deliberately ship without those keys.
