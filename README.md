# Power Log

Power Log records e-bike rides from CYC X6/X12 controller telemetry, GPS and Health data on iPhone, Apple Watch,
Android and the web. Rides stay on the device: inspect live readings, configurable charts and exact observations, then
export a FIT activity or the original-data ZIP. Controller requests are read-only (identity and selected telemetry);
Power Log cannot change drive settings or firmware.

| Platform | Records |
| --- | --- |
| iPhone | Phone recording on iOS 26 or later, with Apple Health saving and the Live Activity |
| Apple Watch | Phone-required companion on watchOS 10 or later: heart rate, controls and native Health saving |
| Android | Android 9 or later, in a foreground service with optional Health Connect saving; no Watch recording |
| Web | Bluetooth and GPS while the page is visible, with Web Bluetooth over HTTPS or localhost; no Health |

## Build and run

Use Node 24 (`export PATH=/opt/homebrew/opt/node@24/bin:$PATH`) and run `npm ci`. `npm run doctor:local` reports the
Node, Xcode, simulator and CocoaPods tools it finds. Maintained native sources live in `modules/` and `apple/`; Expo
prebuild regenerates `ios/` and `android/`.

### Web

`npm run web` serves the app locally. `npm run build:pages` builds the website into `dist/` for the `/power-log/` path,
or `npm run build:pages -- /` for a dedicated hostname; serve only `dist/`, over HTTPS, which Web Bluetooth requires.
Prefer a dedicated hostname for real rides: the GitHub Pages project paths of one account share an origin and
therefore browser storage.

### iPhone and Apple Watch

Install full Xcode and Bundler and set `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`. Copy
[.env.example](.env.example) to `.env.local` for your signing team and bundle identifier, run `bundle install`,
`npm run prebuild:ios` and `pod install` in `ios/`, then `npm run ios` to build and install on a connected iPhone. A
debug build needs `npm start`; use a development build, not Expo Go. `npm run build:ios-simulator` builds for the
simulator. Riding with a bike needs a physical iPhone with Developer Mode.

### Android

Install JDK 17 and the Android SDK with platform tools; set `JAVA_HOME` and `ANDROID_HOME` if they are not detected.

- `npm run build:android -- --preview` builds a release-mode, offline Preview APK as the separate
  `app.powerlog.mobile.preview` app in `artifacts/builds/android/power-log-preview.apk`, signed with the public
  development key; `--arch=arm64-v8a` limits it to one architecture, and `--bundle` builds an App Bundle
  (`power-log-preview.aab`) with native symbol tables instead. Never use that key for a public release.
- `npm run test:android` runs the JVM and Robolectric suites; with `emulator-5554` running, `-- --emulator` adds the
  connected suites. `GRADLE_OPTS=-Dorg.gradle.daemon=false` leaves no Gradle daemon behind.
- `npm run release:android -- <version>` builds, signs and publishes a GitHub release from the maintainer's Mac. It
  reads the signing key from `POWER_LOG_ANDROID_KEYSTORE`, `POWER_LOG_ANDROID_STORE_PASSWORD`,
  `POWER_LOG_ANDROID_KEY_ALIAS` and `POWER_LOG_ANDROID_KEY_PASSWORD`. Keep that key and a backup outside the
  repository: every update must be signed with it. With the Google Play upload key in
  `POWER_LOG_ANDROID_UPLOAD_KEYSTORE`, `POWER_LOG_ANDROID_UPLOAD_STORE_PASSWORD`, `POWER_LOG_ANDROID_UPLOAD_KEY_ALIAS`
  and `POWER_LOG_ANDROID_UPLOAD_KEY_PASSWORD` it also builds the App Bundle for manual upload, and skips it otherwise;
  only the APK is published. The bundle, JS source map and native symbols stay in
  `~/.cache/power-log/releases/<version>/android/`.

## Verify

`sh scripts/verify.sh .` is the everyday check of the working directory; `npm run verify` runs the release tier on a
clean copy of `HEAD`. The testing guide covers [qualification evidence](docs/testing.md#qualification-evidence), the
[Apple Health Lab](docs/testing.md#apple-health-lab) and the [device checklist](docs/testing.md#phase-6-checklist).

## Use and share

Settings holds recording defaults: Watch companion, phone GPS, Health saving, distance source and the 2, 4 or 8 Hz
rate. These freeze at Start. Watch Start waits up to 15 seconds; if unavailable, cancel or start without the Watch.
After an interruption, finish the saved portion or discard it. Local Finish and exports do not wait for Health. History shows each ride's status, summary, charts and exports, and
**Open CSV** reads a ride-data ZIP's `telemetry.csv`. To share on Strava, export the ride's FIT, save it, choose
**Open Strava upload** and select the file on [Strava's website](https://www.strava.com/upload/select).

The [privacy policy](https://AlekseiBespalov.github.io/power-log/privacy) and third-party notices are in Settings →
About and on the website. [Example screenshots](artifacts/app-store/README.md) use fictional rides.

## Project documents

- [AGENTS.md](AGENTS.md): how to change, check and format the code.
- [Architecture](docs/architecture.md): the runtime structure and the code map.
- [Decisions](docs/decisions.md): the product and design choices.
- [Testing](docs/testing.md#qualification-evidence): checks, evidence levels and device procedures.

[Apache-2.0](LICENSE). Free and unsupported, provided as is. No warranty, support or future updates are promised,
subject to applicable law. Measurements may be inaccurate or incomplete; do not rely on the app for safety-critical
decisions.
