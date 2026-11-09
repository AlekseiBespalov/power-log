# Power Log contributor instructions

Power Log records cycling rides from CYC X6/X12 telemetry, GPS and Health on iPhone, Apple Watch, Android and the web.
Shared Kotlin owns the rules, use cases and storage on every platform; React Native/Expo draws the screens for the
phones and the web; small Swift, Android Kotlin and Kotlin/JS adapters reach the OS; the Watch app is SwiftUI.
[docs/architecture.md](docs/architecture.md) describes the layers, slices and modules, and
[docs/decisions.md](docs/decisions.md) the product and design choices: check them before changing behaviour.

## Layout

- `src/`: the screens for phones and web: routes in `app/`, screens, panels and their holders in `features/<slice>/`,
  primitives, chrome and the chart components in `components/`.
- `modules/power-log/`: the Expo module, JS name `PowerLog`; `index.ts` is its only public entry. `src/` holds the
  typed client, the transports, kit's wrappers and, in `exports/`, the FIT, CSV and ZIP encoders and their sinks.
  `ios/` holds the iPhone host and one folder per port (its pod and Swift module are `PowerLogPlatform`);
  `android/src/main/kotlin/app/powerlog/platform/` the Android host and one folder per port.
- `shared/`: one Gradle build of five Kotlin Multiplatform modules, `domain`, `application`, `data`, `kit` and
  `runtime`, configured by the `powerlog.kmp` plugin in `shared/build-logic/` with the version pins in
  `shared/gradle/libs.versions.toml`. Only `data` and `runtime` target Android; the Android module resolves the JVM
  builds of the other three. `runtime` builds `PowerLogCore.xcframework` for the iPhone and keeps the phones' dev tools
  in `src/debugMain`.
- `shared/generated/`, checked in and written only by `npm run build:generated`: `ts/api.ts` (the UI API's TypeScript
  types and hash), `kotlin/` (`ApiHash.kt` and `ApiDispatch.kt`), `js/kit/` (kit's Kotlin/JS build), `js/web/` (the web
  runtime) and `fingerprint.json`, the inputs they were built from.
- `targets/watch/`, `targets/live-activity/`: the SwiftUI Watch app; the Live Activity widget and its intent.
- `plugins/`: Expo config plugins, build integration only.
- `tests/`: the TypeScript suites, Playwright (`e2e/`), the Kotlin/JS browser runner (`browser/kotlin/`) and the shared
  fixtures. Kotlin tests sit in each module's test source sets, Swift suites in `modules/power-log/ios/Tests/` and
  `targets/watch/Tests/`, Android suites in `modules/power-log/android/src/test/`.

Searches skip the generated `shared/generated/js/*/*.mjs`, `package-lock.json` and App Store screenshots (`.ignore`);
use `rg --no-ignore` to include them.

## Hard rules

- Only controller requests 111 (identity) and 50 (selective telemetry). No drive, configuration, calibration,
  firmware or keepalive command, and no arbitrary Bluetooth write API.
- Local recordings are private. Never copy device identifiers, raw identity serials, personal ride locations or
  credentials into tracked fixtures, and never send or upload records without a rider action.
- Until the first public release, a change of the archive's layout or a stored vocabulary raises `ARCHIVE_VERSION`,
  and a change of a cached value's rule raises `RULES_VERSION`; there are no migrations, compatibility readers or dual
  paths. An unreadable store is refused visibly, never erased.
- The rider never hits a dead end: every waiting state offers an explicit action.
- FIT and ride CSV output stays byte-identical to the goldens; only the owner replaces a golden.

## Boundaries

- Dependencies point inward: presentation → application → domain. Data access implements the application's
  repository interfaces and the adapters its ports. Rules live in `domain`, use cases, transactions and failure mapping
  in `application`, storage in `data`; adapters translate and decide nothing.
- Code is named by slice in every layer (`connection`, `recording`, `watch`, `history`, `charts`, `exports`, `imports`,
  `health`, `settings`, `diagnostics`, `shell`), adapters by port, data by data type.
- The UI imports only `modules/power-log`; rider text, units and formats come only from kit.
- The module rules are checked by Konsist's `BoundariesTest` in `shared/runtime/src/jvmTest/` (`kotlin:boundaries`),
  the `no-restricted-imports` blocks of `eslint.config.js` and the Swift scan of
  `scripts/check-apple-release-surface.mjs`. Never add an exception to them.
- Keep the generated `ios/` and `android/` application folders reproducible with Expo prebuild and the config plugins.
  Maintained native sources live in `modules/` and `targets/`; Watch tests must not enter the application source phase.

## Engineering

- Use Conventional Commits: `type(scope): concise imperative description`, with an optional scope. Choose the matching
  type: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `ci`, `build`, or `chore`.
- Code is formatted by Prettier, swift-format and ktfmt at 120 columns (`sh scripts/format.sh`). One statement per line;
  at most one level of nested ternary; typed domain records at boundaries, with metric-keyed maps allowed. There is no
  file-length rule.
- TypeScript strict mode with unused-local/parameter checks; explicit domain types; isolate platform APIs behind typed
  interfaces. Validate imported or untrusted records at the boundary.
- Use actual published Expo-compatible dependency versions and one committed lockfile. Prefer maintained native APIs
  over layering miscellaneous BLE wrappers.
- Add meaningful tests for protocol fixtures, CRC and framing, stale data, reconnects, persistence, imports and exports
  and the ride lifecycle. Do not put tests under route directories or rely on snapshot-only tests. An old test is
  deleted only with its code.
- Run checks appropriate to the change; [docs/testing.md](docs/testing.md) lists the commands and tiers.
  `sh scripts/verify.sh .` is the everyday check: it reruns every check whose inputs changed since its last pass.
  `npm run verify` runs the release tier on a clean `HEAD`. `tests/pending.json` lists the tests still waiting for
  their feature: the everyday tier leaves them out, the release tier runs them and accepts their failures, and fails
  while a listed test passes, so remove an entry in the change that makes its test pass.
- After changing a shared module's `commonMain` or `jsMain`, or the API generator, run `npm run build:generated`;
  `npm run check:generated` fails until the checked-in outputs match their sources.
- Report host, simulator or emulator, linked-build and physical evidence separately. Before installing an iOS build on
  a device, run `npm run test:ios-bundle -- /absolute/path/to/PowerLog.app` and verify signing; never uninstall the app
  to make room for a build, which deletes the rides.
- `docs/` holds only `architecture.md`, `decisions.md` and `testing.md`, as current state. Never restate code in
  Markdown; an external fact the code depends on goes in a short comment at its point of use, with its primary source.
  Do not retain research journals, review rounds or speculative plans. Avoid sprawling abstractions, redundant state
  libraries, placeholder buttons, and claims of untested capabilities.
- No pushes, deployment, cloud builds or account integrations unless requested. Commit only on a local branch of your
  own, never on the main checkout's branch; deliver finished work into the main checkout uncommitted, then delete the
  branches and worktrees you created. Keep maintained code inside this project; device backups and exported personal
  recordings are not source fixtures.
