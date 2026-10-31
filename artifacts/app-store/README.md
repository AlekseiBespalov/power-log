# Screenshots and local demonstration

These are actual app captures using fictional rides, dates and routes. No personal recordings, physical devices or retouched UI are used. Recapture changed screens and check current store requirements before submission; no App Store upload is configured.

Enrollment and publication were deferred by the owner on 2026-10-19. The prepared local media are in
`artifacts/builds/apple/presentation/media/`: `gallery.html`, `PowerLog-demo.mp4`, original PNGs and `manifest.json`.
`artifacts/builds/apple/presentation/PowerLog-media.zip` contains that folder for convenient transfer.

| Current selected set | Count | Dimensions |
| --- | --- | --- |
| iPhone | 11 | 1206 × 2622 |
| iPad | 4 | 2064 × 2752 |
| Apple Watch | 2 | 422 × 514 |

The 51.8-second silent, captioned video combines native screenshots with an uninterrupted recorded layout/heart-rate
comparison sequence. It uses the installed simulator Release app, version 0.4.0/build 1. It demonstrates analysis,
configuration and the completed FIT share sheet; it does not demonstrate a physical Bluetooth connection, a live
Watch workout or saving to Apple Health. The unsigned device archive described in the submission draft is a separate
build. No new physical-device test, installation, upload or publication was performed for these media.

The additional `PowerLog-live-ride.mp4` is a 139.2-second recording of a new simulated ride in the native app, at
normal playback speed. It shows Start, changing readings, Lap, Pause/Resume, inspection-handle drags, zoom, Finish,
the saved ride and FIT export. Only startup was trimmed; the final captured frame is held for three seconds.
`PowerLog-chart-inspection.mp4` is a 27-second excerpt showing the handle and zoom. Neither video inserts still
screenshots into the ride sequence. The original screen recording and runtime checks are retained under
`artifacts/builds/apple/live-demo/`.

`PowerLog-live-ride-fast.mp4` is the 56.27-second app-only cut: the complete native 1206 × 2622 frame with no added
titles, captions, borders or audio. It retains every workflow step, accelerates waits and removes repeated scrolling.
Handle drags run at 1.25×; other sections use 1–5× playback. The final recorded frame is held for 2.5 seconds. The edit
plan and ffmpeg filter are retained as `dynamic-video-plan.json` and `dynamic-filter.txt` under the live-demo directory.

This uses the separate `app.powerlog.lab` simulator build with bundled JavaScript (`Release` configuration and
`ENABLE_TESTABILITY=YES` for the Lab's internal adapter access). It is not a production distribution build. The
simulator-only `lab/apple/PowerLogLiveDemo.swift` source is enabled by the `-PowerLogLiveDemo YES` launch arguments.
It supplies controller-precision readings at 8 Hz through the existing native Lab capture entry point; recording,
storage, charts, controls and export use the normal app implementations. Health saving, GPS and Watch use are off.
The normal generated production Xcode project was restored after capture.

After preparing and validating an isolated Lab simulator app, capture with:

```sh
python3 artifacts/app-store/source/native-shots.py PHONE_SIMULATOR_ID /new/absolute/live-output --live --video
python3 artifacts/app-store/source/native-shots.py PHONE_SIMULATOR_ID /new/absolute/all-metrics-output --all-metrics --video
```

The run retained 864 controller originals, five lifecycle events and a completed ride. The native UI check verified
different cursor readings after dragging horizontally below the lines. Visual review confirmed both power markers
move with the handle. SQLite integrity passed, and Garmin's FIT SDK validated the exported file with 110 records
and two laps. These qualify the simulated demonstration, not physical controller or Health behavior.

The `--all-metrics` capture uses `source/all-metrics.swift` and the additional `-PowerLogDemoAllMetrics YES` Lab
argument. It enables all 31 selectable metrics in Numbers and Graphs before starting, scrolls through the 23 combined
chart cards while recording, then demonstrates inspection, zoom, controls and export. This mode adds fictional GPS,
heart rate and active energy directly through the local archive adapters and live-acquisition evidence. It does not
write to HealthKit or require a Watch. The original default demo remains controller-only.

`PowerLog-all-metrics.mp4` is the 63.3-second app-only cut of that capture. The complete native frame stays intact,
without captions or borders. The local sources, edit timings and checks are in `artifacts/builds/apple/all-metrics-demo/`.
The successful native UI run showed every chart, completed the ride and exported a FIT validated by Garmin's SDK:
1,756 controller originals, 220 locations, 440 physiological observations, 221 FIT records and two laps. This is
synthetic simulator evidence only. The finished video was fully decoded and all chart frames visually checked.

Native capture sources are `source/native-shots.py`, `source/native-shots.swift` and `source/watch-shots.swift`.
Prepare isolated booted simulators with the installed app and fictional data as described below, reset only those
simulators' layout preferences, then run:

```sh
python3 artifacts/app-store/source/native-shots.py PHONE_SIMULATOR_ID /new/absolute/iphone-output --video
python3 artifacts/app-store/source/native-shots.py IPAD_SIMULATOR_ID /new/absolute/ipad-output
python3 artifacts/app-store/source/native-shots.py WATCH_SIMULATOR_ID /new/absolute/watch-output --watch
python3 artifacts/app-store/source/assemble-demo.py /absolute/demo-plan.json /new/absolute/demo-output
```

The runner requires Xcode, Ruby with the repository's `xcodeproj` bundle and an already-installed simulator app. It
does not install an app, load fixtures or address physical devices. The assembler requires ffmpeg and Pillow. Its
JSON plan lists reviewed image/video paths, chapter titles, platform labels and durations; video clips also specify
their start time. Source screens retain their aspect ratio and full frame, with captions placed outside the app.

Review raw video before choosing cuts: this simulator recording omitted some early screen updates, so capture-log
wall-clock markers alone did not identify usable video frames. The selected iPad set excludes captures where a
gesture switched the simulator into a desktop window. Watch startup was reopened through Home after native workout
cleanup to restore the full start screen; no workout was started. These are capture observations, not newly
qualified production behavior. All selected PNGs were visually inspected, normalized to opaque RGB without pixel
changes and checked for their stated dimensions. The finished video was decoded completely and its chapter frames
reviewed. Recapture current screens before any eventual public submission.

The [submission draft](submission.json) contains the English listing, reviewer instructions, proposed privacy answers
and outstanding account details. It is not an App Store Connect submission. Keep private review contacts and account
credentials out of this file. Local archives and new submission captures belong under the ignored
`artifacts/builds/apple/`; simulator fixtures and acceptance evidence belong under `test-results/app-store-preparation/`.
The exact first public build becomes the upgrade baseline when it is released, as specified in `docs/decisions.md`.

## Earlier reference sets

Browser PNGs and their overview sheets are ignored generated output under `artifacts/app-store/screenshots/`; a fresh checkout uses the commands below to create them. The iPhone reference captures and overview remain tracked until their replacement capture is validated. Keep all capture sources and fictional example definitions in Git. These images are not application assets.

| Set         | Captures                                                                                                                               | Local output          |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| iPhone      | Seven 1320 × 2868 PNGs from an iPhone 18 Pro Max simulator: History, summary, effort, battery, temperature, layout editor and Settings | `screenshots/iphone/` |
| Mobile web  | Eight 1320 × 2868 PNGs at 440 × 956 points, 3× scale; also includes live recording                                                     | `screenshots/web/`    |
| Desktop web | The same eight views at 2880 × 1800 pixels (1440 × 900 points, 2× scale), with sidebar/multi-column layout                             | `screenshots/macos/`  |

Optional overview sheets are generated into `screenshots/overview/` after the captures exist.

Both browser sets use dark mode and these filenames:

| File                      | View                                                                |
| ------------------------- | ------------------------------------------------------------------- |
| `01-live-recording.png`   | Running ride with live rider power, battery input power and cadence |
| `02-history.png`          | Three synthetic rides recorded through the browser app              |
| `03-ride-summary.png`     | Ride summary and whole-ride chart statistics                        |
| `04-effort-analysis.png`  | Power chart inspection at an original observation                   |
| `05-battery.png`          | Battery readings and voltage inspection                             |
| `06-temperature.png`      | Motor and controller temperatures                                   |
| `07-custom-dashboard.png` | Ride layout editor with selected metrics and reorder controls       |
| `08-settings.png`         | Display, recording and browser storage settings                     |

macOS captures show the browser app, not a native Mac application. Example values establish no sensor accuracy, physiological calibration or motor performance; routes are illustrations, not recommendations. Native examples are phone-owned local rides with synthetic Watch-origin Health observations; Health saving is disabled. Browser captures use a synthetic CYC controller through the app's real decoder/recording flow; unavailable Health/GPS stay unavailable.

## Reproduce from the repository root

For mobile and desktop web:

```sh
npx playwright test --config artifacts/app-store/source/web-shots.config.ts
```

This starts the web app, records synthetic rides through the UI and writes both sets in about ten minutes. Playwright PNGs have no alpha channel.

The capture server uses `--no-dev --minify` so Settings has no development controls. GPS recording is switched off through Settings; no location or Health readings are injected. Start is pressed inside Ride setup; if the sheet remains open, Done dismisses it while the ride continues. The script verifies that the sheet is closed and recording is running, then waits for the actual ride timer, populated charts, original-observation cursor readings, loaded settings and completed modal animations before capturing; a missing state fails the run. Playwright failure output goes to `test-results/web-shots/`, separately from the iPhone examples.

The shared browser state retains the recorded rides, original samples, lifecycle events, revision records, preferences and chart checkpoints. For each ride/level/bucket it keeps the newest checkpoint, which the current History reader selects; older duplicate checkpoint revisions are omitted from the copy. This keeps Playwright's context restore small without changing the recorded data, selected checkpoints or database schema. Mobile live recording and effort inspection are scrolled to the readings; its live plots and recording controls must fit completely. Desktop live recording preserves the first-viewport framing: app header, timer, Pause/Lap/Finish, view and column controls, both chart headers and visible plotted lines. The scrollable chart cards may continue below the fold. Mobile Battery uses the layout editor to show its voltage graph with all three battery readings; desktop retains all three graphs.

Run the capture command above directly from the checkout in an environment with browser access.

After each run, inspect all sixteen PNGs individually. Verify mobile files are exactly 1320 × 2868 and desktop files 2880 × 1800, with no alpha channel or transparency. Confirm each filename's view is populated, settled and readable, without errors, development overlays or debug banners. Scroll through the app to frame the requested view when needed; report real clipping, overlap or wrong values instead of changing or retouching the UI.

For iPhone:

1. Run `sh artifacts/app-store/source/generate.sh /new/absolute/output/PowerLog`. In a screenshot lane, use a fresh directory under the worktree's `test-results/iphone-shots/`. It builds the macOS shared core and reuses the host test source grouping and build cache (including its test support), then runs the current example writer. It verifies completion, Health origins, summary/plot readability and native export admission, and refuses to overwrite the output directory. Add `--smoke` to generate only three short rides; the everyday screenshot check runs this actual entry point and checks overwrite refusal. The sibling `example-values.json` reports the generated ride IDs, counts and summary values; hand off both paths to the simulator capture owner.
2. Build for an iPhone 18 Pro Max simulator (1320 × 2868). Before installing, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app` and verify its signing. Confirm the installed app remains alive after launch. Stop the app before copying the generated `PowerLog` into its `Library/Application Support/`. Use default layouts/units and Auto distance; do not copy personal data or a live SQLite database.
3. Use `simctl status_bar` for 9:41, full Wi-Fi/battery, then run `python3 artifacts/app-store/source/native-shots.py <simulator-id> /new/absolute/output`. Copy its `01-history`, `02-ride-summary`, `03-power-cadence`, `06-battery-voltage`, `08-temperature`, `09-custom-dashboard` and `11-settings` captures to `screenshots/iphone/` as `01-history`, `02-ride-summary`, `03-effort-analysis`, `04-battery`, `05-temperature`, `06-custom-dashboard` and `07-settings`.
4. Run `xcrun swift artifacts/app-store/source/export-rgb.swift artifacts/app-store/screenshots/iphone/*.png`. This removes unused alpha, rejects transparency and verifies decoded pixels are unchanged.

After capture, generate overview sheets with `python3 artifacts/app-store/source/overview-sheets.py` (Pillow required); only these previews scale the captures. Pass `web macos` to regenerate only the browser overviews, or `iphone` for the native set.

Apple references: [screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications), [product pages](https://developer.apple.com/app-store/product-page/), [review guidelines](https://developer.apple.com/app-store/review/guidelines/).
