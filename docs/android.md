# Android

Power Log uses the same React Native screens, preferences, measurement catalog, chart interactions and typed service interfaces as iPhone. Both phones use the shared bottom tab bar, sheets, switches, chart cards, Reanimated gestures and haptic action service. Native chart backends draw the same scene and exact-marker contract. Android 9+ supplies its own Kotlin implementation of the existing `CycBridge` boundary. Android watch recording is excluded; Health Connect is a phone export destination, not a watch connection.

## Recording

A native foreground service owns Bluetooth polling, GPS, writes and notification controls. Switching apps, locking the screen or dismissing the activity does not depend on a JavaScript timer. Pause, Resume and Finish work from the notification. Android's Force stop, system termination or a device reboot interrupts capture; reopening preserves the committed prefix as an interrupted ride instead of claiming unseen time was recorded.

BLE operations are serialized: connect, discover UART, subscribe, identify, then poll. Only identity and selective telemetry requests are permitted. Polling follows the selected cadence without adding storage time to each interval; it keeps one request outstanding and resets its schedule after a long stall. Requests have deadlines; a recording retries its selected bike with capped backoff. Disconnects retain real gaps. The shared UI's six-second visual hold does not create samples. Replacing the selected bike requires finishing the ride.

Nearby devices and, when selected, precise location are requested from a visible activity. Location uses a foreground service; background-location permission is unnecessary. Notifications are requested for lock-screen controls. See Android's [BLE background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background) and [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

SQLite WAL stores typed original observations, lifecycle events, indexed ride metadata and distance intervals, with persistent 16-second chart/statistics checkpoints. Capture commits small batches; history lists metadata only. Chart reads run on a separate worker, reduce geometry before crossing into JavaScript and retain original IDs for exact inspection. Metadata reads retry a bounded number of times if capture changes their revision; continued contention defers the read and retains the current chart. Canvas rasterizes geometry off the UI thread; gestures transform the accepted image. Auto distance chooses one available source for the ride: phone GPS, otherwise normalized controller speed. Pauses, invalid fixes and connection epochs stop distance integration.

FIT carries rider power, cadence, GPS, distance, timer events and laps. ZIP carries original measurements and lifecycle data. Sharing grants access to a specific export through Android's private FileProvider. No external-storage permission is required. Optional Health Connect saving writes completed rides and measurements in bounded batches with stable record IDs; Power Log retains the local ride if saving fails. A Health Connect route preview is limited to 5,000 fixes; ZIP retains all original fixes. Health Connect access is write-only. See [Health Connect data writing](https://developer.android.com/health-and-fitness/health-connect/write-data).

## Build and test

Install Node 24, JDK 17 and Android Studio's SDK/platform tools. Set `JAVA_HOME` and `ANDROID_HOME` if they are not detected. Generated `android/` is disposable; maintain code in `modules/cyc-bridge/android/`, app configuration and the config plugin.

```sh
npm ci
npm run build:android -- --preview
npm run test:android
# Start an Android emulator before this stress test:
npm run test:android -- --emulator
```

The preview is a release-mode, offline-capable APK signed with the public development key, under the separate `app.powerlog.mobile.preview` package. Output: `artifacts/builds/android/power-log-preview.apk`. `--arch=arm64-v8a` limits a local build to one architecture. Never use the development key for public releases.

The native tests cover wire fixtures, transaction rollback, interrupted recovery, gap-aware distance, bounded plots, exact inspection and export checksums, Health Connect record mapping and marker stability. `ExportFixtureTest` creates only synthetic data for independent FIT decoding and emulator screenshots. The emulator stress test retains eight hours of 8 Hz originals and measures indexed queries. Emulator results do **not** validate real Bluetooth, controller reconnection, radio power consumption or vendor background restrictions. Physical acceptance must include a locked-screen ride across several controller reconnects and compare original timestamps after finishing.

For independent FIT verification, install `garmin-fit-sdk` in a temporary Python environment, retrieve `ExportFixtureTest`'s `verification` directory from the emulator, and run `python tests/android-native/verify_fit.py /path/to/verification`. Compare Android UI screenshots with the iPhone references in `artifacts/app-store/screenshots/iphone/`; test populated and empty cards, cursor selection, sheets and bottom tabs. Exercise permission prompts, a background/screen-off GPS ride, notification controls and FIT/ZIP sharing in the installed release-mode APK as well as the native tests.

## GitHub APKs

The Android workflow builds an installable preview artifact for pull requests, `main` and manual runs. A `v*` tag builds a separately signed release and attaches `power-log.apk` and `SHA256SUMS.txt` to its GitHub release. It does not use a cloud build service.

Release tags must match the version in `package.json`, which also supplies the app version. Before creating a release tag, configure these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded private signing keystore.
- `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

Keep the signing key and a secure backup outside the repository. Updates require the same key. The build fails if release signing is missing; it never substitutes the public debug key. CI decodes the key into its temporary directory and removes it after the build. Release publishing alone receives repository write permission; pull-request builds receive none.

Local signed builds use `POWER_LOG_ANDROID_KEYSTORE`, `POWER_LOG_ANDROID_STORE_PASSWORD`, `POWER_LOG_ANDROID_KEY_ALIAS`, `POWER_LOG_ANDROID_KEY_PASSWORD`, and an increasing `POWER_LOG_ANDROID_VERSION_CODE`. CI uses its workflow run number. Update the version in `package.json` and its lockfile before tagging. See [Android app signing](https://developer.android.com/studio/publish/app-signing).
