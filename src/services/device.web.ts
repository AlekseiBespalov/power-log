import { identifyController, FrameDecoder, requestFrame, toTelemetrySample, UART_NOTIFY, UART_SERVICE, UART_WRITE } from '../core/protocol';
import type { ControllerIdentity } from '../core/protocol';
import type { ConnectionOptions } from '../core/types';
import { ForegroundAdapter } from './foreground-adapter';

interface PendingResponse {
  kind: 'identity' | 'selective';
  deadline: number;
  received: boolean;
  resolve: (payload: Uint8Array) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
}
interface Connection {
  generation: number;
  device: BluetoothDevice;
  writer?: BluetoothRemoteGATTCharacteristic;
  reader?: BluetoothRemoteGATTCharacteristic;
  decoder: FrameDecoder;
  timer?: ReturnType<typeof setTimeout>;
  pending?: PendingResponse;
  onValue: EventListener;
  onDisconnect: EventListener;
  epoch: number;
  sequence: number;
}
const RESPONSE_SECONDS = 2.5;
const timeoutError = () => new Error('CYC response timed out');

export class BrowserAdapter extends ForegroundAdapter {
  readonly kind = 'web' as const;
  readonly description = 'Chrome / Edge on a supported desktop can read the bike while this page stays active. Web cannot guarantee background capture.';
  private device?: BluetoothDevice;
  private connection?: Connection;
  private generation = 0;
  private hz = 2;
  private knownControllers = new Map<string, ControllerIdentity>();

  async setSampleRate(hz: number): Promise<void> {
    if (![2, 4, 8].includes(hz)) throw new Error('Choose 2, 4 or 8 Hz');
    if (this.workoutDeviceId !== null && hz !== this.hz) throw new Error('Finish the ride before changing its sample rate');
    this.hz = hz;
  }

  private current(connection: Connection): boolean {
    return this.connection === connection && this.generation === connection.generation;
  }

  /** Only remove resources belonging to this attempt; a late promise cannot clean up its successor. */
  private cleanup(connection: Connection, disconnectTransport: boolean): void {
    clearTimeout(connection.timer); connection.timer = undefined;
    const pending = connection.pending; connection.pending = undefined;
    if (pending) { clearTimeout(pending.timeout); pending.reject(new Error('Connection ended')); }
    connection.reader?.removeEventListener('characteristicvaluechanged', connection.onValue);
    connection.device.removeEventListener('gattserverdisconnected', connection.onDisconnect);
    connection.reader = undefined; connection.writer = undefined; connection.decoder.reset();
    if (disconnectTransport) connection.device.gatt?.disconnect();
  }

  private failed(connection: Connection, error: unknown, disconnectTransport = true): void {
    if (!this.current(connection)) return;
    this.generation += 1; this.connection = undefined;
    this.cleanup(connection, disconnectTransport);
    this.update({ status: 'error', error: error instanceof Error ? error.message : String(error) });
  }

  private receive(connection: Connection, event: Event): void {
    if (!this.current(connection) || event.target !== connection.reader) return;
    const pending = connection.pending;
    // Timers can be delayed by suspension. Enforce the deadline on the actual arrival path too.
    if (pending && this.now() > pending.deadline) {
      pending.reject(timeoutError());
      return;
    }
    const view = connection.reader?.value;
    if (!view) return;
    try {
      for (const payload of connection.decoder.feed(new Uint8Array(view.buffer, view.byteOffset, view.byteLength))) {
        const response = connection.pending;
        const matches = response?.kind === 'identity' ? payload[0] === 111 || payload[0] === 0 : payload[0] === 50;
        if (!response || response.received || !matches) continue;
        response.received = true; response.resolve(payload);
      }
    } catch (error) { this.failed(connection, error); }
  }

  async startScan(): Promise<void> {
    if (this.workoutDeviceId !== null) throw new Error('Finish the ride before choosing another bike.');
    if (this.connection) throw new Error('Disconnect before choosing another bike.');
    if (typeof navigator === 'undefined' || !navigator.bluetooth) throw new Error('Web Bluetooth is unavailable. Use desktop Chrome / Edge, or the iPhone development build.');
    const generation = ++this.generation;
    this.update({ status: 'scanning', error: undefined });
    try {
      // Keep requestDevice before the first await so the browser retains the user's gesture.
      const selection = navigator.bluetooth.requestDevice({ filters: [{ services: [UART_SERVICE] }, { namePrefix: 'CYCMOTOR' }], optionalServices: [UART_SERVICE] });
      const device = await selection;
      if (generation !== this.generation) return;
      this.device = device;
      this.discovered({ id: device.id, name: device.name ?? 'CYC controller', rssi: 0, ...this.knownControllers.get(device.id) });
      this.update({ status: 'idle' });
    } catch (error) {
      if (generation !== this.generation) return;
      this.update({ status: 'idle' }); throw error;
    }
  }

  async stopScan(): Promise<void> {
    if (this.state.status !== 'scanning') return;
    this.generation += 1; this.update({ status: 'idle' });
  }

  private async request(connection: Connection, kind: 'identity' | 'selective'): Promise<Uint8Array> {
    const writer = connection.writer;
    if (!this.current(connection) || !writer || connection.pending) throw new Error('Bluetooth transport is not ready');
    let resolveResponse!: PendingResponse['resolve'];
    let rejectResponse!: PendingResponse['reject'];
    let rejectDeadline!: PendingResponse['reject'];
    const response = new Promise<Uint8Array>((resolve, reject) => { resolveResponse = resolve; rejectResponse = reject; });
    const deadline = new Promise<never>((_resolve, reject) => { rejectDeadline = reject; });
    const pending: PendingResponse = {
      kind, received: false, deadline: this.now() + RESPONSE_SECONDS, resolve: resolveResponse,
      reject: error => { rejectResponse(error); rejectDeadline(error); },
      timeout: setTimeout(() => {
        if (connection.pending !== pending) return;
        pending.reject(timeoutError());
      }, RESPONSE_SECONDS * 1000),
    };
    connection.pending = pending;
    void response.catch(() => {});
    void deadline.catch(() => {});
    try {
      const bytes = requestFrame(kind);
      const write = writer.properties.writeWithoutResponse
        ? writer.writeValueWithoutResponse(bytes as Uint8Array<ArrayBuffer>)
        : writer.writeValueWithResponse(bytes as Uint8Array<ArrayBuffer>);
      // Either the write or the response may hang. The same deadline bounds both.
      const [, payload] = await Promise.race([Promise.all([write, response]), deadline]);
      if (!this.current(connection)) throw new Error('Connection ended');
      // A response/write promise can settle before a suspended page resumes its continuation.
      if (this.now() > pending.deadline) throw timeoutError();
      return payload;
    } catch (error) {
      pending.reject(error instanceof Error ? error : new Error(String(error)));
      throw error;
    } finally {
      if (connection.pending === pending) connection.pending = undefined;
      clearTimeout(pending.timeout);
    }
  }

  async connect({ deviceId, hz }: ConnectionOptions): Promise<void> {
    this.requireWorkoutDevice(deviceId);
    if (!Number.isFinite(hz) || hz < 1 || hz > 8) throw new Error('Choose a rate from 1 to 8 Hz');
    if (this.workoutDeviceId === null) this.hz = hz;
    if (!this.device || this.device.id !== deviceId || this.state.status === 'scanning') throw new Error('Choose the bike first');
    const device = this.device;
    const previous = this.connection; this.connection = undefined;
    const generation = ++this.generation;
    if (previous) this.cleanup(previous, true);
    const connection: Connection = {
      generation, device, decoder: new FrameDecoder(), epoch: 0, sequence: 0,
      onValue: event => this.receive(connection, event),
      onDisconnect: () => {
        // A queued disconnect event from a superseded attempt can arrive after this GATT has reconnected.
        if (device.gatt?.connected) return;
        this.failed(connection, new Error('The bike disconnected. Reconnect to resume; this gap is retained in any active recording.'), false);
      },
    };
    this.connection = connection;
    device.addEventListener('gattserverdisconnected', connection.onDisconnect);
    this.update({ status: 'connecting', error: undefined, deviceId: device.id, deviceName: device.name, controllerModel: undefined, firmwareLabel: undefined });
    try {
      const server = await device.gatt?.connect();
      if (!this.current(connection)) {
        // connect() itself is not cancellable. Close a late orphan, unless a newer attempt owns this same GATT device.
        if (this.connection?.device !== device) server?.disconnect();
        return;
      }
      if (!server) throw new Error('Bluetooth GATT is unavailable');
      const service = await server.getPrimaryService(UART_SERVICE);
      if (!this.current(connection)) return;
      const writer = await service.getCharacteristic(UART_WRITE);
      if (!this.current(connection)) return;
      connection.writer = writer;
      const reader = await service.getCharacteristic(UART_NOTIFY);
      if (!this.current(connection)) return;
      connection.reader = reader;
      reader.addEventListener('characteristicvaluechanged', connection.onValue);
      await reader.startNotifications();
      if (!this.current(connection)) return;
      const identity = await this.request(connection, 'identity');
      if (!this.current(connection)) return;
      const controller = identifyController(identity);
      this.knownControllers.set(device.id, controller.identity);
      this.discovered({ id: device.id, name: device.name ?? 'CYC bike', rssi: 0,
        controllerModel: controller.identity.controllerModel, firmwareLabel: controller.identity.firmwareLabel });
      connection.epoch = this.now();
      this.update({ status: 'connected', deviceId: device.id, deviceName: device.name ?? 'CYC bike',
        controllerModel: controller.identity.controllerModel, firmwareLabel: controller.identity.firmwareLabel });
      const poll = async (): Promise<void> => {
        if (!this.current(connection)) return;
        const start = this.now();
        try {
          const payload = await this.request(connection, 'selective');
          if (!this.current(connection)) return;
          const values = controller.adapter.decodeTelemetry(payload);
          this.publish(toTelemetrySample(values, { timestamp: new Date().toISOString(), elapsedSeconds: this.now() - connection.epoch, sequence: connection.sequence++ }, controller.identity));
          if (!this.current(connection)) return;
          connection.timer = setTimeout(() => { void poll(); }, Math.max(0, 1000 / this.hz - (this.now() - start) * 1000));
        } catch (error) { this.failed(connection, error); }
      };
      void poll();
    } catch (error) {
      if (!this.current(connection)) return;
      this.failed(connection, error); throw error;
    }
  }

  async disconnect(): Promise<void> {
    this.generation += 1;
    const connection = this.connection; this.connection = undefined;
    if (connection) this.cleanup(connection, true);
    this.update({ status: 'idle', deviceName: undefined, deviceId: undefined, controllerModel: undefined, firmwareLabel: undefined, error: undefined });
  }
}
export const deviceAdapter = new BrowserAdapter();
