import { useEffect, useState } from 'react';
import { Platform, Text, View } from 'react-native';
import { Link } from 'expo-router';
import { Button, colors, type, lineHeight, MIN_TOUCH_TARGET } from '../../components/ui';
import { APP_VERSION } from '../../core/app-info';
import type { BluetoothTraceStatus } from '../../core/bluetooth-diagnostics';
import { deviceAdapter } from '../../services/device';
import { deleteDiagnosticLog, shareDiagnosticLog } from '../../services/files';
import { controllerProtocol, controllerSelectedMask, controllerWireFields } from '../../../shared/js';
import { UART_SERVICE, UART_WRITE, UART_NOTIFY } from '../../services/controller-gatt';

export function BluetoothDiagnosticsControls() {
  const diagnostics = deviceAdapter.bluetoothDiagnostics;
  const [state, setState] = useState<BluetoothTraceStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!diagnostics) return;
    let alive = true;
    let running = false;
    const refresh = async () => {
      if (running) return;
      running = true;
      try {
        const next = await diagnostics.getBluetoothTrace();
        if (alive) setState(next);
      } catch {
        if (alive) setError('Could not read Bluetooth diagnostics. Try again.');
      } finally {
        running = false;
      }
    };
    void refresh();
    const timer = setInterval(() => void refresh(), 1000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [diagnostics]);

  if (!diagnostics) return <Text style={{ color: colors.muted }}>Bluetooth diagnostics are unavailable here.</Text>;
  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
      setState(await diagnostics.getBluetoothTrace());
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Bluetooth diagnostics could not complete. Try again.');
    } finally {
      setBusy(false);
    }
  };
  const exportLog = async () => {
    await diagnostics.stopBluetoothTrace();
    const trace: unknown = JSON.parse(await diagnostics.readBluetoothTrace());
    const protocol = controllerProtocol();
    const frameHex = (request: string) =>
      Array.from(protocol.requestFrame(request), byte => (byte & 255).toString(16).padStart(2, '0')).join('');
    await shareDiagnosticLog(
      JSON.stringify(
        {
          appVersion: APP_VERSION,
          platform: Platform.OS,
          exportedAt: new Date().toISOString(),
          expectedContract: {
            service: UART_SERVICE,
            write: UART_WRITE,
            notify: UART_NOTIFY,
            identityRequestHex: frameHex('identity'),
            telemetryRequestHex: frameHex('telemetry'),
            telemetryMaskHex: controllerSelectedMask().toString(16).padStart(8, '0'),
            fields: controllerWireFields().map(field => ({
              metric: field.metric,
              bit: field.bit,
              width: field.width,
              divisor: field.divisor,
              signed: field.signed,
            })),
          },
          trace,
        },
        null,
        2,
      ),
    );
  };
  const status = state?.capturing ? 'Capturing' : state?.eventCount ? 'Capture stopped' : 'No capture';
  return (
    <View style={{ gap: 12 }} testID="bluetooth-diagnostics">
      <Text style={{ color: colors.muted, fontSize: type.body }}>
        Start capture, reconnect your bike, then export the log.
      </Text>
      <Text style={{ color: colors.muted, fontSize: type.caption }}>
        May include device names and serials. Shared only when you export.
      </Text>
      <Text accessibilityLiveRegion="polite" style={{ color: colors.text, fontSize: type.body }}>
        {status}
        {state && state.eventCount > 0 ? ` · ${state.eventCount} events` : ''}
        {state?.stopReason === 'timeLimit' ? ' · Time limit reached' : ''}
        {state?.stopReason === 'sizeLimit' ? ' · Size limit reached' : ''}
      </Text>
      {state?.capturing ? (
        <Button disabled={busy} onPress={() => void run(() => diagnostics.stopBluetoothTrace())}>
          Stop capture
        </Button>
      ) : (
        <Button
          disabled={busy || !state}
          onPress={() =>
            void run(async () => {
              await deleteDiagnosticLog();
              await diagnostics.startBluetoothTrace();
            })
          }
        >
          {state?.eventCount ? 'New capture' : 'Start capture'}
        </Button>
      )}
      <Link
        href="/"
        style={{
          color: colors.accent,
          fontSize: type.body,
          lineHeight: lineHeight.body,
          paddingVertical: 12,
          minHeight: MIN_TOUCH_TARGET,
        }}
      >
        Back to ride
      </Link>
      <View style={{ flexDirection: 'row', gap: 12 }}>
        <View style={{ flex: 1 }}>
          <Button secondary disabled={busy || !state?.eventCount} onPress={() => void run(exportLog)}>
            Export log
          </Button>
        </View>
        <View style={{ flex: 1 }}>
          <Button
            secondary
            disabled={busy || !state}
            onPress={() =>
              void run(async () => {
                await diagnostics.clearBluetoothTrace();
                await deleteDiagnosticLog();
              })
            }
          >
            Clear
          </Button>
        </View>
      </View>
      {error && (
        <View style={{ gap: 8 }}>
          <Text accessibilityRole="alert" style={{ color: colors.red, fontSize: type.body }}>
            {error}
          </Text>
          <Button secondary disabled={busy} onPress={() => void run(async () => {})}>
            Refresh status
          </Button>
        </View>
      )}
    </View>
  );
}
