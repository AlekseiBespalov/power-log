# Power Log

Cycling rides and CYC X6/X12 telemetry for iPhone, Apple Watch and the web. Record bike measurements with available GPS/Health data, inspect configurable charts and export rides. Controller access is read-only; Power Log cannot change motor settings, calibration or firmware.

## Features

- Start, pause, resume, lap, save or discard rides; delete saved rides from History.
- Compare rider/motor power, cadence, speed, battery use and temperatures. Configure numbers and charts per view, inspect exact samples and peaks, and pan/zoom long rides.
- Set recording defaults, distance source and speed units in Settings. Recording choices apply to the next ride; analysis preferences also apply to saved rides.
- Keep data locally and share only when you choose. [Example screenshots](artifacts/app-store/README.md) use fictional rides.

| Platform | Capabilities |
| --- | --- |
| iPhone | Native Bluetooth, optional GPS/Apple Health, Watch recording, Live Activities, FIT and original ZIP exports (including telemetry CSV) |
| Apple Watch | HealthKit/GPS, ride controls and automatic transfer to iPhone |
| Web | Foreground Bluetooth recording, IndexedDB history, CSV import/export and responsive charts |

Android is not implemented; macOS uses the web app. Web Bluetooth needs a compatible browser and HTTPS or localhost. Keep the page active while recording; browser storage can be cleared or evicted.

Motor input power is electrical battery input, separate from rider power. Missing measurements remain unavailable; [measurement rules](docs/measurements.md) explain units and distance sources.

## Run on the web

Use Node 24 (`.nvmrc`).

```sh
npm ci
npm run web
```

The [website workflow](.github/workflows/pages.yml) builds, checks and deploys pushes to `main` to GitHub Pages. Set the repository's Pages source to **GitHub Actions**. For a local Pages build, run `npm run build:pages`; only `dist/` is published. See [public hosting](docs/security.md) for paths, environment isolation and privacy.

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

Use an Expo development build, not Expo Go. Debug uses Metro (`npm start`); standalone Release builds come from `ios/PowerLog.xcworkspace` with the matching Watch companion. The full phone workout engine requires iOS 26; Watch requires watchOS 10. Edit maintained native sources/configuration, not generated `ios/` or `android/` projects.

Native schema upgrades currently require reinstalling, so export important rides before an incompatible update. [Testing](docs/testing.md) covers bundle/signing checks, installation and physical background-recording acceptance. Run `npm run check` for the shared checks and web build.

## Export to Strava

On iPhone: **History → saved ride → Export FIT → Save to Files**, then **Open Strava upload** and select the file on [Strava's website](https://www.strava.com/upload/select). Export becomes available after saving/syncing finishes. No Strava account setup, API credentials or backend are needed in Power Log. Browser rides export CSV only.

## Documentation

| Document | Purpose |
| --- | --- |
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
