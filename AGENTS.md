# Power Log contributor instructions

Power Log records cycling rides from CYC X6/X12 telemetry, GPS and Health. React Native/Expo shares the phone UI;
Swift owns iPhone capture and the SwiftUI Watch companion, Kotlin owns Android's foreground service, and web uses
foreground capture with IndexedDB. Product and design decisions are in [docs/decisions.md](docs/decisions.md); check
them before changing behaviour.

`shared/` supplies pure Kotlin Multiplatform `telemetry` (decoder in `telemetry.cyc`), `gps`, `analytics`,
`inspection`, `presentation`, `completion`, `lifecycle`, `health` and `protocol` packages. iPhone consumes
`PowerLogCore.xcframework`, Android the JVM artifact, TypeScript generated `shared/js/`. Platform adapters own
IO, transactions, clocks and scheduling. The [code map](docs/architecture.md#code-map) lists the files behind each
concept. Searches skip generated `shared/js/*.mjs`, `package-lock.json` and App Store screenshots (`.ignore`); use
`rg --no-ignore` to include them.

## Hard rules

- Only controller requests 111 (identity) and 50 (selective telemetry). No drive, configuration, calibration,
  firmware or keepalive command, and no arbitrary Bluetooth write API.
- Local recordings are private. Never copy device identifiers, raw identity serials, personal ride locations or
  credentials into tracked fixtures, and never send or upload records without a rider action.
- Until the first public release every change is made in place, with no migrations, compatibility readers or dual
  paths; an unreadable store is still refused visibly, never erased.
- The rider never hits a dead end: every waiting state offers an explicit action.

## Boundaries

- Keep routes thin in `src/app/`; domain code in `src/core/`; feature UI in `src/features/`; reusable primitives in `src/components/`; platform access in `src/services/` and `modules/power-log-bridge/`.
- Use the shared metric catalog, MonitorSource query boundary and multi-lane chart component for live and History charts.
- Keep platform-specific permissions, Health providers and capabilities in adapters.
- Keep native generated `ios/` and `android/` application folders reproducible with Expo prebuild/config plugins. Maintained native sources live in `modules/` and `apple/`; Watch tests must not enter the application source phase.

## Engineering

- Use Conventional Commits: `type(scope): concise imperative description`, with an optional scope. Choose the matching type: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `ci`, `build`, or `chore`.
- Code is formatted by Prettier, swift-format and ktfmt at 120 columns (`sh scripts/format.sh`). One statement per line; at most one level of nested ternary; typed domain records at boundaries, with metric-keyed maps allowed. There is no file-length rule.
- TypeScript strict mode with unused-local/parameter checks; explicit domain types; isolate platform APIs behind typed interfaces. Validate imported/untrusted records at the boundary.
- Use actual published Expo-compatible dependency versions and one committed lockfile. Prefer maintained native APIs over layering miscellaneous BLE wrappers.
- Add meaningful tests for protocol fixtures, CRC/framing, stale data, reconnects, persistence, imports/exports and record lifecycle. Do not put tests under route directories or rely on snapshot-only tests.
- Run checks appropriate to the change. `sh scripts/verify.sh .` is the everyday check: in parallel, it reruns every check whose inputs changed since its last pass. `sh scripts/verify.sh . --full` (working directory) and `npm run verify` (clean HEAD) run the release tier: every check regardless of earlier passes, plus emulator, simulator, app-build, generation and soak checks, macOS/iOS common tests, native Watch Release links and `*VolumeTest` classes; it requires emulator-5554, and `POWER_LOG_SKIP_CONNECTED=1` makes it explicitly partial. `node scripts/checks.mjs --list [--full]` lists the checks. After changing shared Kotlin, run `npm run build:core-js`; `npm run check:core-js` fails until the checked-in JS matches its sources. `npm run verify:stress` runs the live-ride soak at full workload. Report host, simulator/emulator, linked build and physical evidence separately.
- Apple Lab: `POWER_LOG_LAB=1 npm run build:ios-simulator` builds `app.powerlog.lab`; `sh scripts/test-lab-apple.sh --simulators` runs phone/Watch crash paths directly with `xcrun simctl` after Health authorization, using the booted pair in `POWER_LOG_LAB_PHONE_UDID` and `POWER_LOG_LAB_WATCH_UDID`. Android instrumentation uses `app.powerlog.bridge.test` on emulator-5554. Production Release rejects Lab code; physical checks remain in [docs/testing.md](docs/testing.md#phase-6-checklist).
- Before installing an iOS build, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app` and verify signing. After installation, confirm the process remains alive and inspect the app when possible; a successful launch request is not proof of successful startup.
- `docs/` holds only `architecture.md` (overall design and code map), `decisions.md` (crucial choices) and `testing.md` (commands, tiers, device checks). Never restate code in Markdown; an external fact the code depends on goes in a short comment at its point of use, with its primary source. Do not retain research journals, review rounds or speculative plans. Avoid sprawling abstractions, redundant state libraries, placeholder buttons, and claims of untested capabilities.
- No commits, pushes, deployment, cloud builds or account integrations unless requested. Keep maintained code inside this project; device backups and exported personal recordings are not source fixtures.
