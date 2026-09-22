# CYC telemetry and recording contract

The browser decoder is in `src/core/protocol.ts`; the native decoder is in `modules/cyc-bridge/ios/CycProtocol.swift`. Golden inputs and expected values are in [protocol.json](../tests/fixtures/protocol.json); [fixture provenance](../tests/fixtures/README.md) describes their origin.

## Allowed controller operations

The app can construct exactly two requests. There is no arbitrary controller-write method.

| Operation | Command payload | Complete frame |
| --- | --- | --- |
| Identity | `6f` (111) | `02 01 6f 9d 49 03` |
| Selected measurements | `32 03 c0 fb 8f` (50, mask `0x03c0fb8f`) | `02 05 32 03 c0 fb 8f 5a 1a 03` |

Connect to Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; write uses `0002` and notifications use `0003` at the same UUID suffix. Resolve a supported X6 or X12 identity before starting polling, keep one request outstanding, and reject mismatched response types/masks. Rate is 1–8 Hz, with 2 Hz the UI default. No drive, configuration, calibration, firmware, full-values or keepalive command is available to the app.

Identity replies accept command 111 or 0. The first NUL after the command/version bytes must occur within 128 bytes. The strict printable prefix must match an X6 or X12 model followed by a 6–8 digit firmware label with an optional capital-letter suffix. Opaque bytes can follow that prefix before the NUL. Only the sanitized model/firmware and version bytes are exposed; opaque identity/serial data remains private. Both controller adapters use the same selected telemetry layout. Model identity does not identify the motor generation.

## Framing and selected fields

Framing, CRC and the selective-values request are the VESC packet protocol of the controller's `bldc`-derived firmware: command 50 is `COMM_GET_VALUES_SELECTIVE` and command 0 is `COMM_FW_VERSION` in [VESC `datatypes.h`](https://github.com/vedderb/bldc/blob/master/datatypes.h). The field selection mask, field scaling and identity string are CYC's. The payload is framed as `[02, u8 length, payload, u16 CRC, 03]` for 1–255 bytes or `[03, u16 length, payload, u16 CRC, 03]` for 256–1024 bytes. Lengths, CRC and telemetry values are big-endian. CRC is XMODEM polynomial `0x1021`, initial zero; ASCII `123456789` produces `0x31c3`. Reject zero, oversized or noncanonical long lengths, invalid CRC and bad terminators. The streaming decoder tolerates fragmentation, coalescing and corrupt prefixes, resets partial state at reconnect, limits incoming chunks to 64 KiB and retains only a bounded incomplete frame.

Selective replies begin with command 50 and their 32-bit mask. Fields follow ascending bit order. The decoder rejects unknown mask bits, a mask different from the expected one, missing bytes and trailing bytes. `toTelemetrySample` requires every selected field and valid timing; missing fields never become zero.

| Bit | Sample field | Wire type | Divisor |
| --- | --- | --- | --- |
| 0 | `controllerTempC` | signed 16-bit | 10 |
| 1 | `motorTempC` | signed 16-bit | 10 |
| 2 | `motorCurrentA` | signed 32-bit | 100 |
| 3 | `batteryCurrentA` | signed 32-bit | 100 |
| 7 | `motorRpm` | signed 32-bit | 1 |
| 8 | `batteryVoltageV` | signed 16-bit | 10 |
| 9 | `consumedAh` | signed 32-bit | 10000 |
| 11 | `consumedWh` | signed 32-bit | 10000 |
| 12 | `cadenceRpm` | signed 32-bit | 10000 |
| 13 | `throttleVoltageV` | signed 32-bit | 100 |
| 14 | `pedalTorqueNm` | signed 32-bit | 100 |
| 15 | `faultCode` | unsigned 8-bit | 1 |
| 22 | `humanPowerW` | signed 32-bit | 1 |
| 23 | `speedRaw` | signed 32-bit | 100 |
| 24 | `raceMode` | unsigned 8-bit | 1 |
| 25 | `assistLevel` | unsigned 8-bit | 1 |

`motorInputPowerW` is derived from battery voltage times **battery current**, rounded to four decimal places. It is electrical battery input, not mechanical motor output or human power. Signed measurements are retained. CYC cadence and rider power are controller-reported smoothed values.

## Controller speed units

For exact model names `X6` and `X12` reporting protocol version `5.3`, selective field 23 is km/h. The published [CYC firmware archive](https://github.com/CYC-MOTOR/CYC-firmware/blob/d97bb381d11a7f7a0d2d93f2fc1612887ba59bc1/bldc-release_5_03-cyc%20v2.7z) sends `app_get_speed()` at scale 100 in `commands.c`; `applications/speed_sensor.c` returns `speed_km_h`, calculated from wheel RPM and configured wheel diameter in `applications/app_control.c`. The km/h/mph display preference is separate. Reading configuration is unnecessary and the request allowlist remains unchanged.

Capture retains `speedRaw` and adds `controllerSpeedMps = speedRaw / 3.6`, plus sanitized `controllerModel`, `firmwareLabel` and `controllerProtocol`. Native and browser capture use the same profile rule. Model variants and other protocol versions retain originals and identity but omit normalized speed. This is a source-backed profile, not a guarantee for unpublished custom firmware. Physical accuracy still depends on the controller's wheel/speed-sensor configuration; Power Log does not calibrate it.

The monitor displays known speed in the user's km/h, mph or m/s preference. GPS and controller speed share a Speed chart when both are selected, with separate lines and original observations. Controller speed does not replace GPS speed, HealthKit route speed or FIT speed. It can contribute the separately identified **Controller estimate** distance profile under the [distance selection rules](measurements.md). Recordings without normalized samples show **Controller speed (unit unknown)**. Old rows are not reinterpreted using whichever bike happens to be connected now. Mixed-profile recordings display only their normalized observations on the physical-unit chart; original raw values remain exportable.

## CSV, validation and summaries

The normalized CSV header is `SAMPLE_COLUMNS` in [types.ts](../src/core/types.ts). Source is recording metadata (`device`); import requires an explicit source argument and cannot authenticate origin from CSV contents. Exported numeric values and UTC timestamps cannot contain spreadsheet formulas. Files remain local until the user explicitly exports/shares them.

The parser recognizes the current Power Log column set, the original 20-column Power Log set, and Python prototype columns, including reordered columns. Current CSV adds optional `controllerSpeedMps`, `controllerModel`, `firmwareLabel` and `controllerProtocol`; unavailable fields are empty. Imports preserve those fields and validate normalized speed against its supplied profile and original raw value. CSV provenance remains caller-supplied, not authenticated. It accepts quoted fields, UTF-8 BOM and CRLF, and rejects duplicate/unknown/missing columns, malformed quoting, wrong row widths, nonnumeric/nonfinite values and missing required measurements. Python `+00:00` UTC timestamps normalize to `Z`; native schema timestamps must already use UTC `Z`. Historical Python full-command-4 exports can be analyzed, but the app cannot issue that request.

Limits are 32 MiB per CSV, 200,000 rows, 256 characters per cell and seven days of elapsed time. Numeric magnitudes are bounded, sequence numbers are nonnegative safe integers, wire integer/byte fields are checked, and battery input watts must agree with voltage times battery current. A valid header-only zero-sample recording is allowed. Export uses the canonical native column order.

Each timestamp must be a valid ISO UTC calendar time. Sequence and elapsed time must strictly increase within one recording. Real UTC can move after an operating-system clock correction, so wall-clock jumps and metadata end times earlier than starts are preserved. Summary `clockDiscontinuities` reports intervals with nonpositive wall-clock progress or a wall/monotonic difference exceeding 2.5 seconds. Elapsed time remains authoritative for ordering, duration and integration.

Summaries separately report total span, covered time, long gaps, missing sequence numbers, peak rider power/cadence and covered-interval averages. Energy uses trapezoids only where adjacent elapsed times differ by at most 2.5 seconds. Outages add no energy and no synthetic zero samples. Empty or single-sample records have no time-weighted average; they return `null`, with zero integrated energy. The two energy channels remain human energy and battery input energy.

## Verification boundary

Offline tests check CRC/framing, exact allowlisted bytes, identity, signed/scaled fields, sanitized live packets, malformed layouts, counter wrap, stale/reconnect behavior, CSV boundaries and summary calculations. See [testing](testing.md) for native build, physical Bluetooth and locked-screen capture checks.
