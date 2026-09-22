# Security and public hosting

## Data boundaries

Power Log keeps rides on the device. The browser uses IndexedDB for recordings and localStorage for preferences; it has no telemetry-upload service, analytics, remote fonts or map tiles. CSV imports are parsed locally. Downloads and native sharing require a user action. Exported rides can contain location, health and controller information; treat them as private files.

Bluetooth access requires the user's permission. The controller transport only requests identity and selected measurements; it cannot configure, calibrate or update a motor. Strava sharing uses manual FIT export: the upload-page shortcut opens Strava in the browser without sending a recording, and Power Log never handles Strava credentials.

Browser recordings are not encrypted by the application. Anyone with access to that browser profile, an extension with suitable permissions, or JavaScript running on the same origin may access them. Native files use iOS data protection. Neither protects against a compromised device. Browser storage may be cleared or evicted; export important rides.

## Publish the web application

```sh
npm ci
npm run build:pages
```

This builds `dist/` for `/power-log/` and includes `.nojekyll`, `LICENSE.txt` and `THIRD_PARTY_NOTICES.txt`; the notices are generated from the bundle's source maps, which are themselves withheld from `dist/`. The build environment excludes developer shell variables and local dotenv files, so signing settings and `EXPO_PUBLIC_` values from a developer's machine cannot reach the public bundle. For a dedicated hostname, use `npm run build:pages -- /`. Only the resulting `dist/` is the website artifact; never serve the repository root, a development server or a native build directory.

Use HTTPS; browser Bluetooth requires it. GitHub Pages serves static files publicly, and the host receives ordinary request metadata such as IP addresses.

**Prefer a dedicated hostname for real ride storage.** Project paths such as `owner.github.io/power-log/` and `owner.github.io/another-app/` share one origin and therefore share browser storage; a different path or database name is not isolation. A custom subdomain separates this application from other sites on the account. Changing the hostname later also changes which browser storage is available. References: [GitHub Pages HTTPS](https://docs.github.com/en/pages/getting-started-with-github-pages/securing-your-github-pages-site-with-https), [same-origin storage](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Same-origin_policy).

## Dependency overrides

`package.json` overrides `decode-uri-component` to 0.5.0 under `query-string` ([advisory](https://github.com/advisories/GHSA-vcc3-ghjq-m6fr)) and `uuid` to 11.1.1 under `xcode` ([advisory](https://github.com/advisories/GHSA-w5hq-g745-h8pq)); `patches/query-string+7.1.3.patch` adapts the router's import to the fixed decoder. `npm run test:security` binds both. Remove each override, and the patch with it, once the upstream package depends on a fixed version.
