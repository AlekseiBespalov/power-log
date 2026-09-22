# Screenshot examples

These are actual app captures using fictional rides, dates and routes. No personal recordings, physical devices or retouched UI are used. Recapture changed screens and check current store requirements before submission; no App Store upload is configured.

## Sets

| Set | Captures | Overview |
| --- | --- | --- |
| [iPhone](screenshots/iphone/) | Seven 1320 × 2868 PNGs from an iPhone 17 Pro Max simulator: History, summary, effort, battery, temperature, layout editor and Settings | [Preview](screenshots/overview/iphone-overview.png) |
| [Mobile web](screenshots/web/) | Eight 1320 × 2868 PNGs at 440 × 956 points, 3× scale; also includes live recording | [Preview](screenshots/overview/web-overview.png) |
| [Desktop web](screenshots/macos/) | The same eight views at 2880 × 1800 pixels (1440 × 900 points, 2× scale), with sidebar/multi-column layout | [Preview](screenshots/overview/macos-overview.png) |

macOS captures show the browser app, not a native Mac application. Example values establish no sensor accuracy, physiological calibration or motor performance; routes are illustrations, not recommendations. Native fixtures/seals carry generated provenance. Browser captures use a synthetic CYC controller through the app's real decoder/recording flow; unavailable Health/GPS stay unavailable.

## Reproduce from the repository root

For mobile and desktop web:

```sh
npx playwright test --config artifacts/app-store/source/web-shots.config.ts
```

This starts the web app, records synthetic rides through the UI and writes both sets in about ten minutes. Playwright PNGs have no alpha channel.

For iPhone:

1. Run `sh artifacts/app-store/source/generate.sh /new/absolute/output/PowerLog`. It uses the production archive and shared example writer, verifies seals and refuses to overwrite the output directory.
2. Build for an iPhone 17 Pro Max simulator. Stop the app before copying the generated `PowerLog` into its `Library/Application Support/`. Use default layouts/units and Auto distance; do not copy personal data or a live SQLite database.
3. Use `simctl status_bar` for 9:41, full Wi-Fi/battery, then launch. Capture History, ride summary, effort, Battery, Temperature, the layout editor and Settings with `xcrun simctl io <simulator-id> screenshot --type=png --mask=ignored <output.png>`.
4. Run `xcrun swift artifacts/app-store/source/export-rgb.swift artifacts/app-store/screenshots/iphone/*.png`. This removes unused alpha, rejects transparency and verifies decoded pixels are unchanged.

Regenerate overview sheets with `python3 artifacts/app-store/source/overview-sheets.py` (Pillow required); only these previews scale the captures.

Apple references: [screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications), [product pages](https://developer.apple.com/app-store/product-page/), [review guidelines](https://developer.apple.com/app-store/review/guidelines/).
