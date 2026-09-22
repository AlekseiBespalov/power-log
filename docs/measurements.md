# Measurement sources and distance

Power Log retains original observations and derives analysis from a fixed recording revision. Values use SI units in storage: meters, seconds and meters per second. Display units do not alter originals. Missing observations are not zero, and brief visual holds never enter calculations or exports.

## Ride distance

**Ride distance** is the covered subtotal from one selected producer. Chart points, range statistics, saved totals, lap distances and FIT use the same versioned distance profile. Distance is cumulative; its mean and time integral are not useful ride statistics. A selected range reports the distance travelled over supported intervals and their coverage.

Automatic selection uses this order:

| Ride | Source order |
| --- | --- |
| Outdoor | Owner GPS, other recorded GPS, owner associated Health intervals, other associated Health intervals, controller estimate |
| Indoor | Associated Health intervals, then controller estimate |

The owner is the Watch when Watch recording was selected, otherwise the iPhone. Automatic selection requires at least one valid interval; a valid stationary interval qualifies. A short partial owner profile can outrank a longer alternative. The app does not mix producers or fill a GPS profile's holes with controller values. Only sources actually recorded are available: selecting Watch recording does not also activate phone GPS.

Settings provides one distance source preference for current and saved rides on this installation. Auto chooses by the rules above; an explicitly chosen source remains unavailable if it has no eligible intervals. Each summary, chart request and export captures this selection; historical per-ride selections do not override it. Selection changes derived analysis, not original data, Apple Health or previously uploaded activities. Browser rides currently provide controller distance only.

Each profile carries source, method, policy version, covered seconds and missing coverage. The UI identifies partial distance and controller estimates. Average speed is distance divided by covered active time. When coverage is partial, FIT omits the ordinary session average-speed field rather than presenting that average as covering the whole ride.

## GPS distance

The common native GPS accumulator accepts finite coordinates with horizontal accuracy from 0 to 50 meters. Adjacent observations must lie in the same active interval and capture epoch, be at most 10 seconds apart and imply no more than 40 m/s. These are application filters, not guarantees of sensor accuracy.

Distance uses the great-circle surface distance between accepted fixes. Both endpoints with valid speed below 0.5 m/s identify a stationary interval with zero increment. Missing speed does not establish stationarity. Pauses, rejected fixes, discontinuities and gaps break the sequence. Capture preserves quality failures or a continuity barrier so a later read cannot bridge around a discarded location. Older archives cannot recover rejection evidence that their recorder never stored.

Phone Health writes use accepted GPS increments. Saving to Health does not change the independently derived local distance. Watch and iPhone live distance read the same local projection off the recording executor; recovery rebuilds or advances that projection from retained inputs.

## Health distance

**Health reported distance** is separate from Ride distance. The UI labels a builder total provisional; stored data retains its producer and reporting time. A final Health workout total replaces its provisional reported total, including a downward correction. A total alone does not establish a distance timeline.

Fallback intervals require the exact cycling-distance identifier, meters, finite nonnegative amounts, positive duration and evidence of association with the saved Health workout. A local-device/time-window query alone does not establish that association. The app records association evidence from saved-workout queries without changing an existing raw sample's payload.

A single raw quantity with `sampleCount=1` or a raw series child can contribute. Condensed parents containing multiple values cannot contribute alongside children. Overlapping clusters, intervals crossing activity boundaries and intervals implying more than 40 m/s are excluded. Builder snapshots and final totals are never summed as increments.

Health amounts are indivisible at arbitrary chart or lap boundaries. A range includes only fully contained amounts and identifies unresolved boundary coverage. FIT omits a lap distance when a Health amount crosses that lap boundary. GPS ranges use a constant-rate approximation between accepted GPS fixes; controller ranges integrate the clipped linear speed model. These boundary calculations are derived values, never original sensor observations.

## Controller estimate

Controller distance integrates original normalized speed using the trapezoidal rule. Both endpoints must have a supported known-unit profile and speeds from 0 to 40 m/s, with at most 2.5 seconds between them. Identity, connection, clock and active interval must remain continuous. Pauses, reconnects, resets, unknown units and missing samples break integration.

Known units do not prove wheel circumference, tire calibration or ground travel. The value is therefore labeled **Controller estimate**, including indoors, where a stationary bike can report wheel or motor-derived speed. Historical recordings lacking explicit connection evidence identify that limitation. The app never guesses a unit from `speedRaw` or adds held UI readings.

## Other measurements

| Measurement | Meaning |
| --- | --- |
| Rider power / pedal torque / cadence | Separate CYC observations; rider power is not motor power and crank cadence is not motor RPM. |
| Motor input power | Battery voltage × battery current: electrical input, not mechanical motor output. |
| Battery and motor current | Distinct circuit quantities; matching units do not make them interchangeable. |
| Battery and throttle voltage | Distinct scales and circuits. |
| Battery charge used (Ah) / energy used (Wh) | Consumption reported by the controller, not automatically ride consumption; reset and baseline scope are controller-defined. |
| Component temperatures | Controller or motor temperature, not ambient or body temperature. |
| Heart rate | Available Health observations; raw quantity/series data takes precedence over builder snapshots. |
| Active/resting energy | Cumulative Health metabolic estimates, separate from electrical Wh and mechanical joules. Snapshots are not summed. |
| GPS speed | Core Location instantaneous speed; explicit negative speed accuracy invalidates it. Missing historical accuracy is unknown. |
| Controller speed | Normalized known-profile speed, or a separate unit-unknown raw channel. |
| Health cycling speed | A separately named Health quantity, not relabeled GPS speed. |
| Altitude / accuracy | Altitude is not ascent. Coordinate, altitude, speed and course uncertainties are distinct. |
| Course | Direction of travel, not device heading. North wrap breaks plotted continuity; arithmetic averaging/subtraction is suppressed. |
| Assist / mode / fault | Step or categorical observations, without arithmetic means or integrals. |

Physical speed sources may share a chart axis after conversion to the same display unit. Their observations, times, provenance and gaps remain separate.

## Evidence and limits

Apple documents [coordinate accuracy](https://developer.apple.com/documentation/corelocation/cllocation/horizontalaccuracy) and [speed accuracy](https://developer.apple.com/documentation/corelocation/cllocation/speedaccuracy) separately. Its [cycling distance type](https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/distancecycling) is cumulative; [condensed workout samples](https://developer.apple.com/documentation/healthkit/accessing-condensed-workout-samples) require distinguishing parent quantities from their series. [Strava's distance documentation](https://support.strava.com/en-us/articles/15401893-how-distance-is-calculated) similarly distinguishes recorded distance streams from GPS calculations and wheel circumference effects.

Source tests establish calculation and data consistency. They do not establish physical wheel calibration, GPS accuracy or Apple background/radio behavior. See [storage](storage.md), [chart architecture](architecture.md#chart-queries-and-rendering) and [testing](testing.md) for query and validation boundaries.
