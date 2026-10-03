# Power Log

Cycling rides and CYC X6/X12 telemetry for iPhone, Android, Apple Watch and the web. Record bike measurements with available GPS/Health data, inspect configurable charts and export rides. Controller access is read-only; Power Log cannot change motor settings, calibration or firmware.

## Features

- Start, pause, resume, lap, save or discard rides; delete saved rides from History.
- Compare rider/motor power, cadence, speed, battery use and temperatures. Configure numbers and charts per view, inspect exact samples and peaks, and pan/zoom long rides.
- Set recording defaults, distance source and speed units in Settings. New installations record on the phone; Apple Watch recording is opt-in. Recording choices apply to the next ride; analysis preferences also apply to saved rides.
- Keep data locally and share only when you choose. [Example screenshots](artifacts/app-store/README.md) use fictional rides.

| Platform | Capabilities |
| --- | --- |
| iPhone | Native Bluetooth, optional GPS/Apple Health, Watch recording, Live Activities, FIT and ride-data ZIP exports |
| Android | Native Bluetooth, phone GPS, optional Health Connect saving, recording notification controls, FIT and ride-data ZIP exports |
| Apple Watch | HealthKit/GPS, ride controls and automatic transfer to iPhone |
| Web | Foreground Bluetooth recording, IndexedDB history, FIT and ride-data ZIP exports, CSV import and responsive charts |

Web Bluetooth needs a compatible browser, such as Chrome on Android, and HTTPS or localhost. Keep the page active while recording; browser storage can be cleared or evicted. Browser distance settings offer Auto and Controller estimate.

Motor input power is electrical battery input, separate from rider power. Missing measurements remain unavailable; [measurement rules](docs/measurements.md) explain units and distance sources.

## Run on the web

Use Node 24 (`.nvmrc`).

```sh
npm ci
npm run web
```

The [website workflow](.github/workflows/pages.yml) builds each push to `main` and deploys it to GitHub Pages. It runs no tests: run `npm run verify` before pushing ([testing](docs/testing.md)). Set the repository's Pages source to **GitHub Actions**. For a local Pages build, run `npm run build:pages`; only `dist/` is published. See [public hosting](docs/security.md) for paths, environment isolation and privacy.

## Develop for iPhone and Watch

Install full Xcode with iOS/watchOS SDKs, Ruby 3.2+ and Bundler. Configure your Apple developer team in ignored `.env.local`, using [.env.example](.env.example).

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
npm ci
bundle install
npm run doctor:local
npm run prebuild:ios
(cd ios && bundle exec pod install)
npm run ios
```

Use an Expo development build, not Expo Go. Debug uses Metro (`npm start`); standalone Release builds come from `ios/PowerLog.xcworkspace` with the matching Watch companion. Phone-owned rides record, show History and export from iOS 16.4; Watch-owned rides need iOS 17 with HealthKit and watchOS 10; phone-owned Health saving and the Live Activity need iOS 26. Edit maintained native sources/configuration, not generated `ios/` or `android/` projects.

`package.json` supplies the app version for the phone, Watch and Live Activity. Set `POWER_LOG_IOS_BUILD_NUMBER` to a positive integer before prebuild for their shared build number (default `1`).

Until the first public release, storage formats change in place: reinstall the phone and Watch apps together and clear browser site data after an update that changes them. [Testing](docs/testing.md) covers bundle/signing checks, installation and physical background-recording acceptance. Run `npm run check` for the shared checks and web build.

## Install on Android

Download `power-log.apk` from the [latest release](https://github.com/AlekseiBespalov/power-log/releases/latest) and open it on Android 9 or newer. Release APKs are signed for updates; development previews install separately as **Power Log Preview**.

## Develop for Android

With Android Studio, JDK 17 and Node 24 installed:

```sh
npm ci
npm run build:android -- --preview
```

This produces an installable release-mode preview APK. [Android setup](docs/android.md) covers emulator tests, background recording, optional Health Connect and publishing signed APK releases from the Mac. Android watch recording is not included.

## Export to Strava

**History → saved ride → Export FIT**, then save the shared or downloaded file, then **Open Strava upload** and select the file on [Strava's website](https://www.strava.com/upload/select). Export becomes available after saving/syncing finishes. No Strava account setup, API credentials or backend are needed in Power Log. **Export ZIP** saves the ride's original data as CSV tables with a descriptor ([format](docs/storage.md#ride-data-zip)).

## Documentation

The [privacy policy](https://AlekseiBespalov.github.io/power-log/privacy) is also available in **Settings → About → Privacy policy** (`/privacy`). About shows the app version and links to GitHub Issues, the source and the published website’s third-party notices.

| Document | Purpose |
| --- | --- |
| [Android](docs/android.md) | Native build, recording and GitHub APK releases |
| [Architecture](docs/architecture.md) | Code map, native/UI boundaries and charts |
| [Storage](docs/storage.md) | Lifecycle, Watch synchronization, deletion and exports |
| [Measurements](docs/measurements.md) | Values, distance sources and calculation rules |
| [Protocol](docs/protocol.md) | Controller allowlist, wire format and CSV validation |
| [Testing](docs/testing.md) | Checks, benchmarks and device acceptance |
| [Security](docs/security.md) | Private data and public hosting |

[Contributor instructions](AGENTS.md) define development boundaries.

## License

[Apache-2.0](LICENSE). Free and unsupported, provided as is. No warranty, support or future updates are promised, subject to applicable law. Measurements may be inaccurate or incomplete; do not rely on the app for safety-critical decisions.

Third-party components retain their own licences. The website build writes `LICENSE.txt` and a generated `THIRD_PARTY_NOTICES.txt` into `dist/` beside the app.
