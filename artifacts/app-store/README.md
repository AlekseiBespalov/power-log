# Screenshot examples

These are actual app captures using fictional rides, dates and routes. No personal recordings, physical devices or retouched UI are used. Recapture changed screens and check current store requirements before submission; no App Store upload is configured.

## Sets

Browser PNGs and their overview sheets are ignored generated output under `artifacts/app-store/screenshots/`; a fresh checkout uses the commands below to create them. The iPhone reference captures and overview remain tracked until their replacement capture is validated. Keep all capture sources and fictional example definitions in Git. These images are not application assets.

| Set         | Captures                                                                                                                               | Local output          |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| iPhone      | Seven 1320 × 2868 PNGs from an iPhone 17 Pro Max simulator: History, summary, effort, battery, temperature, layout editor and Settings | `screenshots/iphone/` |
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
2. Build for an iPhone 17 Pro Max simulator. Before installing, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app` and verify its signing. Confirm the installed app remains alive after launch. Stop the app before copying the generated `PowerLog` into its `Library/Application Support/`. Use default layouts/units and Auto distance; do not copy personal data or a live SQLite database.
3. Use `simctl status_bar` for 9:41, full Wi-Fi/battery, then launch. Capture History, ride summary, effort, Battery, Temperature, the layout editor and Settings with `xcrun simctl io <simulator-id> screenshot --type=png --mask=ignored <output.png>`.
4. Run `xcrun swift artifacts/app-store/source/export-rgb.swift artifacts/app-store/screenshots/iphone/*.png`. This removes unused alpha, rejects transparency and verifies decoded pixels are unchanged.

After capture, generate overview sheets with `python3 artifacts/app-store/source/overview-sheets.py` (Pillow required); only these previews scale the captures. Pass `web macos` to regenerate only the browser overviews, or `iphone` for the native set.

Apple references: [screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications), [product pages](https://developer.apple.com/app-store/product-page/), [review guidelines](https://developer.apple.com/app-store/review/guidelines/).
