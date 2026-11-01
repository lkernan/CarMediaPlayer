# CarMediaPlayer — MG4 build

A fork of lkernan/CarMediaPlayer for the MG4 (SAIC head unit, firmware
`1100 SWI131 R27 09.08.24`).  It **replaces the stock media app**
(`com.saicmotor.media`, shared system UID, signed with the AOSP test platform
key) and plays **only from a Navidrome server**.  History starts at 1.0, the first stable
release (the alpha versions before it, 2.0–2.0.14, aren't kept).

## Releases

- A release is the signed APK, published as a GitHub release on this repo:
  tag `v<versionName>`, the APK attached as
  `CarMediaPlayer-<versionName>-MG4-platform-signed.apk`.  The **Release**
  workflow (`.github/workflows/release.yml`) does this by itself when a push
  to `master` carries a versionName that has no release yet: it builds, runs
  the tests, signs with the AOSP test key (downloaded, checked against the
  pinned SHA-256s) and publishes, with the version commit's message as the
  notes.  Don't publish by hand.  Settings → *Check for updates*
  (`update/Updater.kt`) installs the latest release, so bump the version only
  in a commit that's ready to go out.  The GitHub release is the delivery:
  don't send the APK separately, just say the version is on its way and
  check that the workflow published it.
- Bump `versionCode` (+1) and `versionName` in **both** `app/build.gradle` and
  `tools/buildcheck/app/build.gradle`.  versionName goes 1.0 → 1.0.1 → 1.1 …
  and must keep rising (Check for updates compares names).  versionCode
  carries on from the alpha versions (1.0 is 2000000018) and must keep
  rising too, or the head unit refuses the update.
- The version commit's message is the release's notes (the workflow drops
  the title line): **short, plain dot points only** — a few words each,
  one per change, no explanations (e.g. "- Autoplay when car starts.").
  1.0's notes are just "Initial Release".
- README.md is the GitHub page: a short description (with credit to
  lkernan), one sentence on the car and firmware it was tested on, the
  Features list, a one-line uninstall note, then the screenshots.  The
  owner edits it by hand, so keep their wording and layout (plain
  sentences, no extra headings) and show any change before pushing it.
  Features are short plain dot points; change them only when a feature is
  added or removed.  Release notes never go there.
- Commit to `master`.  Changes are never pushed to lkernan/CarMediaPlayer
  (the upstream project).
- Bug reports come as a logcat from the car, sometimes with Navidrome server
  logs.  The app logs under tag `CarMedia`; the SAIC launcher logs as
  `MediaView`, `SwcManager`, `LauncherService`, `HardKeyService`.
- The repo is public: never commit logcats, server logs, server addresses,
  usernames, passwords, personal emails, place names or the signing keys.
  Tests and screenshots use made-up data (`music.example.com`, invented
  artists and albums).

## Build, test, sign

`tools/buildcheck` builds the app's sources with the same dependencies as
`app/build.gradle` and runs the Robolectric tests in `tools/buildcheck/tests`
(27 service tests, 20 screenshot tests → `tools/buildcheck/app/build/shots`).
Needs JDK 17, Gradle 8.x, Android SDK platform 34 + build-tools 34
(`local.properties` with `sdk.dir=...`).

```
cd tools/buildcheck
gradle :app:testDebugUnitTest :app:assembleRelease
tools/sign.sh <keys-dir> <out-dir>    # from the repo root
```

Keys: `platform.pk8` + `platform.x509.pem` from AOSP
`build/target/product/security` (github.com/aosp-mirror/platform_build).
Signer SHA-256 must be `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
Never commit keys (`app/keys/` is ignored).

Both test classes must keep the same `@Config(sdk=[28], qualifiers=...)`, or
Robolectric fails with FileSystemAlreadyExistsException when run together.

## How the car talks to the app (learned from the firmware)

- **Launcher mode commands**: the launcher calls `startService` on
  MediaService with extra `"command"` = a
  `com.saicmotor.common.CommandParcelable` (int event, String pkg, int type,
  int state, Bundle).  Events: 32769 switch source, 32773 play, 32774 pause,
  16417 source available (types USB1 0, USB2 1, BT 2, ONLINE 3; state 1/0).
  See `common/CommandParcelable.kt`, `service/SaicModeBridge.kt`.
- **Start-up**: the launcher restores the last source ~10–15 s after boot and
  retries every 3 s about 5 times, then falls back to radio.  The app must
  report availability (Settings.System `usb1AvailableStatus` etc. + event
  16417 to LauncherService), which `SaicModeBridge.reportAvailable` does.
  (The launcher also names a `com.saicmotor.hardkey` HardKeyService, but
  that app isn't on this firmware.)
  The last source is recorded from the audio-focus bundle
  `key_car_source_type=6` (→ type 0).
- **Direct boot**: user storage unlocks ~25 s after boot, too late for the
  launcher.  MediaService, the four `mbs` browse services and BootReceiver are
  `directBootAware`; everything they read is in device-protected storage
  (`data/Storage.kt`).  Widgets can't be updated until unlock.
- **Steering wheel** (broadcast `com.saic.keyevent.hardkey.report`, extras
  `android.intent.extra.hardkey.keycode/down/longpress`): 300/299 next/prev,
  301 centre = play/pause, 297/298 volume.  The launcher's SwcManager calls
  `play()` on any volume press if the session isn't PLAYING, so the app
  ignores a "play" that arrives just after a volume change (`isVolumeChangePlay`).
  Holding left/right: the launcher sends fastForward/rewind then
  `__STOP_FAST_FORWARD__`.
- **STAR key (17)**: its release is never reported (the system intercepts
  it).  The launcher's SwcManager counts keys held (`mCallPress`) and only
  acts when a release brings the count to 0, resetting it after each
  release — so the first wheel key after a STAR press is ignored.  The app
  mirrors the count (`mirrorLauncherKeyCount`) and, if the launcher hasn't
  acted within 500 ms, does what the launcher would have done
  (`coverIgnoredKey`).  Volume keys are lost the same way: after 300 ms
  with no change to the car's volume, the app turns it itself
  (`coverIgnoredVolumeKey`, `CarVolume` — the launcher's own call:
  CarAdapterService queryClient(10) → ICarAudioService volumeUp/volumeDown
  with flag 1024, which picks the volume in use and shows the volume bar).
  A held key still works for the launcher; only a short press is lost.
  The driver has STAR mapped to Back (Button Mapper) and uses it all the
  time, e.g. to leave YouTube.
- **Wheel keys while YouTube is open**: the launcher keeps sending them to
  us (its source), as "play" for the centre key.  The centre key plays /
  pauses the app being watched (`wheelTarget` — the app that took the
  speakers, playing or paused, until its session goes away when it's
  closed); next / previous / seek do nothing then.  Otherwise the keys are
  the music's.
- **Launcher now-playing tile** (`MediaView`): uses `METADATA_KEY_ALBUM_ART_URI`
  via Glide if present (slow), else the `ALBUM_ART` bitmap; so the app sends
  only the bitmap + DISPLAY_ICON_URI.  Any state other than PLAYING (3) shows
  as paused, so buffering while playWhenReady is reported as PLAYING at speed 0.
- Other apps (YouTube) keep priority: external "play" is blocked while
  another session is playing.
- **Updating itself**: as a platform-signed system-UID app it holds
  INSTALL_PACKAGES, so `Updater` installs through a PackageInstaller session
  with no prompt (if the installer asks for confirmation anyway, its screen
  is shown).  The system kills the app to replace it; `BootReceiver` gets
  MY_PACKAGE_REPLACED and reopens the app, and resumes the music if it was
  playing — only when the updater started the install.

## Navidrome

- Navidrome identifies a "player" by client name + User-Agent and sets
  transcoding per player (this app shows up as "CarMediaPlayer [Dalvik/Android]").
  **Don't set a custom User-Agent** on stream requests: it creates a new
  player with no transcoding (FLAC).  Cache key is `song-v2:<id>` for that reason.
- Transcoded streams have no Content-Length and don't support ranges.
- Navidrome only draws a couple of covers at a time and answers "429 Too
  Many Requests" when too many arrive at once (the album grids).  The image
  loader asks again after 0.5 s and 1.5 s (`data/RetryWhenBusy.kt`), and a
  cover that still fails is fetched again later instead of never.

## Playback design (MediaService)

- Media3 1.3.1 ExoPlayer over a 400 MB SimpleCache (`service/MediaCache.kt`,
  512 KB fragments so a power cut loses little).
- A continuous downloader fetches the next 10 songs while playing
  (`startDownloader`), pausing while the current song has < 10 s buffered.
- `WaitForNetworkPolicy` retries through dead spots instead of failing.
- Start-up restores the queue from a snapshot in device storage and plays
  without the network; in shuffle it starts at the first fully cached song.
- Start-up **always plays**, however the music was left at switch-off (the
  car pauses it itself when switched off, and the launcher then asks for
  "paused").  Only the "Don't play anything" setting, or another app
  already playing, keeps it quiet.
- Failed scrobbles are queued and retried (`PendingScrobbles`).
- The log shows how each start-up went ("Startup timing: …").
