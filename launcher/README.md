# Tomboy home (dumbtv home, Physeek style)

A home screen for Fire TV in the style of the Physeek app: a light grey page, a top
bar, a live preview, and a row of sliding panels. It shows your HDMI inputs, and your apps one press away.

- **Plain Java and Android views.** No AndroidX, Kotlin, Compose, Play Services or
  any other library.
- **Network for one thing only.** `INTERNET` is there for YouTube's embedded player,
  used only for a SmartTube video carried over a game; nothing else goes online.
- **Two normal permissions on Fire OS 7 and 8.** `MODIFY_AUDIO_SETTINGS` (the music
  ceiling) and `INTERNET` (the video above), both granted at install with no prompt.
  It also declares three (usage access, task order, boot completed), each limited to
  Android 8.1 and earlier, for the Fire OS 6 Keeper below. Check: `aapt2 dump permissions dumbtv-home.apk`.
- **No ads, no recommended rows, no account, no analytics, no update checks.**

## The screen

- **Top bar.** ☰, "Tomboy" (`Home.WORDMARK`), then **Apps** (every app as a grid)
  and **Settings** (Fire TV's settings) at the left; the clock in light grey at the
  right. ☰ opens a menu: Options and Amazon's home screen. The margin round the
  screen (28 dp) is the same at the top, the sides and under the panels.
- **Panels.** Nothing is selected when the home screen comes up; the first press on the
  remote (except ▲, which goes to the top bar) only wakes it. The selected panel glows
  white round its top and sides. A row of equal panels at the bottom, one margin up from the screen
  edge with the reflection fading past it, that slides as you move, the selected one in the middle, each standing on a reflective floor. No
  titles or text around them.
- **Input names.** An input is named after the device on it when the device says
  who it is over HDMI-CEC (a PS5 shows as "PS5", not "HDMI 2"). That needs CEC on
  both ends: on the TV, Settings › Display & Sounds › HDMI CEC Device Control; on a
  PS5, Settings › System › HDMI › Enable HDMI Device Link. Otherwise the TV's own
  name for the input is used, and Rename (hold OK) always wins.
- **AirPlay.** The last panel of the inputs row; it always stays last. It opens
  Settings › Display & Sounds › Apple AirPlay & HomeKit, Apple's AirPlay screen on
  Fire TVs with AirPlay built in (`Catalog.AIRPLAY_SCREEN`). Where that is missing it
  looks for an app or package named for AirPlay or HomeKit, then falls back to the
  TV's settings. `airplay.app=` in home.conf overrides it, as a package or as
  `package/activity` (read one off the TV with
  `adb shell dumpsys window | grep mCurrentFocus`).
- **Inputs button.** Fire OS handles the remote's Inputs button itself, like HOME. List
  the window it opens in home.conf as `guard.replace=package/class` (read it off the
  TV with `adb shell dumpsys window | grep mCurrentFocus` while that screen shows), and
  Guard replaces it with this home screen, as it does Amazon's home.
- **Greeting.** A greeting in light grey in the middle of the screen whenever the
  home screen comes up, until the first press on the remote; then the preview takes its
  place. It is picked at random from the lines for the time of day in
  `res/raw/greetings.txt` (early, morning, afternoon, evening, night). Edit a copy and
  push it to `/sdcard/Android/media/tv.dumbtv.home/greetings.txt` to change them
  without a rebuild. `{name}` becomes Dryden, or `greeting.name=` from home.conf.
- **Live preview.** Above the panels, the selected input's picture appears once it
  arrives, muted, after the selection rests for half a second. A device that is off
  shows nothing; for an app, its banner shows. Options › Input preview: **Hologram**
  (default: it flickers on, with fixed scan lines, rising bands of transparency, a
  sweep now and then and short glitches that jolt it sideways), **Plain**
  (a fade), or **Off**. The video itself cannot be warped: the TV's hardware draws it
  under the app, so the hologram is layered over it. Round the preview, a glow in its
  colours: a 64x36 copy of the picture, twice a second, blurred, reaching about 16 dp past the edges, at full brightness with nearly its own saturation. Where the TV will not
  share the picture (often for HDCP sources), the glow is a soft neutral white.
- **Music over inputs.** With music playing (Spotify or any app with a media session),
  open an input from its panel and the music keeps going over it. On the remote:
  ⏯ plays and pauses, ⏩ and ⏪ skip songs, ☰ shows the card. Channel ▲ ▼ move the
  music's ceiling in 1 dB steps (0 to −15 dB, 6 dB down to start, remembered): an
  equalizer on the music app's own audio session, so the game's sound stays as it was and
  the TV's volume stays the master. It catches the session when the music app announces it
  for equalizers; when none was heard it falls back to the output mix. The ceiling stays
  on after the input closes, until channel ▲ takes it back to 0 dB. This needs MODIFY_AUDIO_SETTINGS, a normal permission. A frosted glass card with the square album art and the song and artist scrolling under it (the ceiling bar while channel ▲ ▼ are pressed) slides
  in at the bottom right and leaves after five seconds; it also shows when the song
  changes. It needs notification access for `tv.dumbtv.home/.Listener`, which install.sh
  grants (the listener reads no notifications). Options › Music over inputs turns it off.
  Options › Mini-player: **On changes** (a few seconds after a press or a new song) or
  **Always**; Mini-player size: Small, Medium, Large; Mini-player position: any corner.
- **☰ on an input** opens the app the music card shows (Spotify, SmartTube), on its
  now-playing screen; with nothing playing, SmartTube (any build: it is found by name).
  One Home from there goes straight back to the input (Options › Home twice › Home
  screen turns that off too).
- **SmartTube over a game.** ☰ on an input, pick a video in SmartTube, press Home: the
  input comes back with the same video in a corner, from where it was (YouTube's embedded
  player, sized and placed like the music card). The TV's hardware decoder draws on the
  video plane the HDMI input holds, so the picture is decoded in software instead
  (res/raw/tube.js: VP9 up to 720p through WebCodecs, drawn on a canvas); the player's own
  video plays on unseen for the sound. SmartTube itself can't float over an HDMI
  input on this TV: Fire OS frees its memory and closes it as the input's picture starts.
  ⏯ pauses, ⏩ ⏪ skip 10 s, channel ▲ ▼ set its volume (the ceiling), Back closes it.
  Some videos can't be embedded; those say so and close.
- **Home twice.** From another app, pressing Home twice within a few seconds goes straight
  back to the input last shown: pick music in Spotify, Home Home, and the game is back with
  the music over it. Options › Home twice › Home screen turns it off.
- **Apps shelf.** Off by default (Options › Apps shelf); it also shows whenever
  there are no inputs to show, as on a Fire TV stick, so the home screen is never
  empty.

| Remote | Does |
|---|---|
| ◀ ▶ on the panels | slide to the next panel |
| ▲ ▼ | between the panels and the top bar |
| OK | open the app, or switch to the input |
| Hold OK, or ☰ Menu on a panel | Move, Rename, Hide, App info |
| Move mode | ◀ ▶ moves the panel (and ▲ ▼ a line, on the Apps grid), any other key drops it |
| ☰ Menu elsewhere | the top bar's menu |
| Back | from the Apps grid: back home. At home: the first panel |
| On an input: Back | back to the home screen |
| Hold HOME | Fire OS quick menu (Amazon's, unchanged) |

**Options**, from ☰ › Options, cycles each setting with ◀ ▶ or OK:

| Setting | Choices |
|---|---|
| Panel size | small, medium, large (also the number of grid columns: 5, 4, 3) |
| App order | your order, or recently used, newest first |
| Apps shelf | hidden (default), shown |
| Inputs shelf | shown, hidden |
| Input preview | hologram, plain, off |
| Music over inputs | on, off |
| Open inputs with | Automatic, the TV's app, dumbtv home (see below) |

Below them: which apps and inputs appear, a reset for order, names and hidden
panels, and **Open Amazon's home screen**. Apps without a TV banner, like the
Appstore, start hidden; tick them to add them.

## Install

You need `adb` and a TV with ADB debugging on.

```sh
adb connect <tv-ip>:5555         # accept "Allow USB debugging?" on the TV
./launcher/install.sh            # download, check the SHA-256, install, take over HOME
```

The APK comes from this repository's
[releases](https://github.com/humblemuzzu/dumbtv/releases/latest), and `install.sh`
refuses it if the checksum differs from the one in `version.env`. Installing the
APK by hand works, but HOME only switches over once `install.sh` (or
`scripts/launcher.sh`) has set up the HOME hook described below.

Undo: `./launcher/install.sh --undo` gives HOME back to Amazon's launcher.

### Without build tools (Windows, Git Bash)

GitHub Actions builds the APK on every push to the fork. With the repository secrets
`DUMBTV_KEYSTORE` (the keystore, base64) and `DUMBTV_KEYPASS` set, each build is
signed with the same key and installs over the last one.

1. GitHub › Actions › the latest run › **Artifacts** › `dumbtv-home-apk`; unzip it.
2. In Git Bash, from the repository:

```sh
adb connect <tv-ip>:5555
adb uninstall tv.dumbtv.home        # only the first time, to replace a release install
./launcher/install.sh --apk ~/Downloads/dumbtv-home-apk/dumbtv-home.apk
```

`--apk` needs only adb: it installs the file and sets up the HOME takeover.

## HDMI inputs

**Options › Open inputs with** decides what an input tile does:

- **Automatic** (default): the TV's own app, when exactly one app other than
  Amazon's Live TV claims the input; otherwise the launcher's own screen. Amazon's
  Live TV claims every input and then opens its settings or a channel scan.
- **The TV's app**: always hand the input to the TV's app, as releases up to 1.1
  did.
- **dumbtv home**: always use the launcher's own screen. It uses Android's public
  `TvView`: the picture, the input's name for a moment, and "No signal" when nothing
  is connected. Back returns to the home screen, and HOME releases the input at
  once. Nothing new is requested to do this.

## The settings file

Everything lives in one text file on the TV:

```
/sdcard/Android/data/tv.dumbtv.home/files/home.conf
```

```ini
# small medium large
tiles=medium
# custom recent
order=custom
# off on
apps.shelf=off
# on off
inputs=on
# holo on off
preview=holo
# auto tv home
switch=auto
apps.order=com.amazon.firetv.youtube,org.videolan.vlc
apps.hide=org.videolan.vlc
apps.show=com.amazon.venezia
inputs.hide=com.droidlogic.tvinput/.services.AV1InputService/HW1
name.com.droidlogic.tvinput/.services.Hdmi2InputService/HW6=PS5
```

Comments go on their own line; changes apply the next time the home screen comes
back. On Fire OS 8 (Android 11) `/sdcard/Android/data/<app>` is closed to `adb`, so
push the file to the launcher's media folder instead; it is applied once and
remembered:

```sh
adb push home.conf /sdcard/Android/media/tv.dumbtv.home/home.conf
```

## How HOME works on Fire OS

Fire OS has no working "default launcher" setting, and Amazon's launcher is a
protected package. Measured on a Fire OS 7 television:

1. **The HOME key cannot be intercepted.** Fire OS handles it before any
   accessibility key filter runs. So `Guard`, a small accessibility service, waits
   for Amazon's home window instead. It sees window changes only and reads no
   screen content.
2. **Android delays app launches for 5 s after HOME** (`APP_SWITCH_DELAY_TIME`),
   which is why other launchers flash Amazon's home. A launch fired by
   AlarmManager comes from `system_server` and is exempt.
   `install.sh` sets `min_futurity=0` so the alarm fires at once; `--undo` removes
   it. Up to Android 11 that is `settings global alarm_manager_constants`; Android
   12 and later ignore it and read `device_config` namespace `alarm_manager`.
3. **Amazon's home is covered before it draws.** `Guard` keeps an overlay ready,
   with no surface while idle so it costs nothing during video, and removes it once
   dumbtv home is on screen.
4. **Fire OS 8 (Android 11) only lets an app with a visible window start an
   activity.** The cover is that window, so `Guard` launches once it has drawn.

HOME from an app shows about 0.3 s of black, then this home screen.

### Fire OS 6

Fire OS 6.7 refuses third-party accessibility services, so it uses `Keeper`, a
small foreground service:

1. UsageStats (`appops set tv.dumbtv.home GET_USAGE_STATS allow`, granted by
   `install.sh`) tells it when Amazon's launcher comes to the front.
2. `ActivityManager.moveTaskToFront` brings dumbtv home back. That is not an
   activity launch, so the 5 s delay does not apply: under 0.25 s with a real
   remote.
3. At boot there is no dumbtv task yet, so the first start uses an alarm.

**Options › Open Amazon's home screen** pauses the takeover until another app is
opened.

## Build it yourself

Needs JDK 17 and the Android SDK (`platforms;android-25`, `build-tools`). There is
no Gradle: `build.sh` runs aapt2, javac, d8, zipalign and apksigner directly, and
compiles against Android 7.1 so one APK runs on Fire OS 6, 7 and 8.

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-25" "build-tools;34.0.0"
./launcher/build.sh              # launcher/build/dumbtv-home.apk
./launcher/install.sh --build    # build, install, take over HOME
```

On Linux, set `ANDROID_HOME`; `build.sh` finds a JDK 17 in
`/usr/lib/jvm/java-17-openjdk` (Arch: `pacman -S jdk17-openjdk`). It prefers JDK 17
because build-tools 34's `d8` has crashed on classes from newer JDKs.

Your build is signed with a key `build.sh` creates on first run, and Android will
not update the release with it or the other way round. `install.sh` says so; back
up `home.conf`, then `adb uninstall tv.dumbtv.home`.

## Diagnostics

Logcat has everything under the tag `dumbtvtest`. For problems at boot, when the
log has rotated by the time you can read it, the Keeper can also write a file that
survives reboots:

```sh
adb shell touch /sdcard/Android/data/tv.dumbtv.home/files/debug
```

While that file exists, log lines go to `files/keeper.log` in the same folder.
Delete both to stop.

## Files

| File | What it is |
|---|---|
| `src/.../Home.java` | the home screen: top bar, clock, preview, panels, Apps grid, menus, move mode |
| `src/.../Shelf.java` | the sliding row of panels: spring glide, reflection |
| `src/.../Preview.java` | the live, muted preview of the selected input |
| `src/.../Greetings.java` | picks the greeting for the time of day from greetings.txt |
| `src/.../Music.java` | the playing music app: song, art, play, pause, skip |
| `src/.../Ceiling.java` | the music's volume ceiling over inputs: an equalizer on its audio session |
| `src/.../MiniPlayer.java` | the music card over an input |
| `src/.../Listener.java` | the notification access that music control needs |
| `src/.../Glow.java` | the coloured glow round the preview |
| `src/.../Holo.java` | the hologram over the preview: scan lines, bands, flicker, glitches |
| `src/.../Covers.java` | the face of a panel: banner, icon and name, or an input card |
| `src/.../Tile.java` | one app on the Apps grid, with its name and grey line |
| `src/.../Grid.java` | the Apps grid |
| `src/.../IconButton.java` | the top bar's square buttons |
| `src/.../InkMenu.java` | Physeek's dark pop-up menu |
| `src/.../Catalog.java` | which apps and inputs exist, their names (CEC device names), and their order |
| `src/.../Item.java` | one app or input |
| `src/.../Input.java` | the built-in input screen (`TvView`) |
| `src/.../Config.java` | reads and writes `home.conf` |
| `src/.../Options.java` | the settings screen |
| `src/.../Ui.java` | Physeek's colours and type |
| `src/.../Guard.java` | the Fire OS 7 and 8 HOME takeover |
| `src/.../Keeper.java` | the Fire OS 6 HOME takeover |
| `src/.../Recent.java` | when each app was last opened, for App order › Recently used |
| `src/.../BootReceiver.java` | restarts the Keeper after a boot or an update |
| `build.sh` | aapt2, javac, d8, zipalign, apksigner |
| `install.sh` | download and checksum (or `--build`), install, HOME hook, `--undo` |
| `version.env` | the version and the release's SHA-256, read by the three scripts |
| `release-check.sh` | checks that the version, the built APK and the GitHub release agree |
| `tests/RecentTest.java` | host-side test for the Recently used order, run by CI |

## Releasing

1. Bump `VERSION`, `VERSION_CODE` and `VERSION_NAME` in `version.env`.
2. `./build.sh`, copy `build/dumbtv-home.apk` to `build/dumbtv-home-<VERSION>.apk`,
   and put its SHA-256 in `version.env`.
3. Upload it as `dumbtv-home.apk` to a release tagged `launcher-v<VERSION>`.
4. `./launcher/release-check.sh` fails if the tag, the APK and the hash disagree.
   CI runs `--manifest` on every push.
