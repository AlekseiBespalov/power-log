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
interface BikeSession {
  device: BluetoothDevice;
  epoch?: number;
  sequence: number;
  attempts: number;
  established: boolean;
}
interface Connection {
  generation: number;
  session: BikeSession;
  writer?: BluetoothRemoteGATTCharacteristic;
  reader?: BluetoothRemoteGATTCharacteristic;
  decoder: FrameDecoder;
  timer?: ReturnType<typeof setTimeout>;
  cancelSetup?: () => void;
  pending?: PendingResponse;
  onValue: EventListener;
  onDisconnect: EventListener;
  stableSince?: number;
}
const RESPONSE_SECONDS = 2.5;
const SETUP_SECONDS = 15;
const STABLE_SECONDS = 30;
const IDLE_RETRIES = 5;
const timeoutError = () => new Error('CYC response timed out');

export class BrowserAdapter extends ForegroundAdapter {
  readonly kind = 'web' as const;
  readonly description = 'A supported browser, including Chrome on Android, can read the bike while this page stays active. Web cannot guarantee background capture.';
  private device?: BluetoothDevice;
  private session?: BikeSession;
  private connection?: Connection;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private generation = 0;
  private hz = 2;
  private knownControllers = new Map<string, ControllerIdentity>();

  override setWorkoutOwner(deviceId: string | null): void {
    const previous = this.workoutDeviceId;
    super.setWorkoutOwner(deviceId);
    if (previous !== null && deviceId === null && this.state.status === 'reconnecting') void this.disconnect();
  }

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
    connection.cancelSetup?.(); connection.cancelSetup = undefined;
    const pending = connection.pending; connection.pending = undefined;
    if (pending) { clearTimeout(pending.timeout); pending.reject(new Error('Connection ended')); }
    connection.reader?.removeEventListener('characteristicvaluechanged', connection.onValue);
    connection.session.device.removeEventListener('gattserverdisconnected', connection.onDisconnect);
    connection.reader = undefined; connection.writer = undefined; connection.decoder.reset();
    if (disconnectTransport) connection.session.device.gatt?.disconnect();
  }

  private failed(connection: Connection, error: unknown, disconnectTransport = true): void {
    if (!this.current(connection)) return;
    this.generation += 1; this.connection = undefined; this.session = undefined;
    clearTimeout(this.retryTimer); this.retryTimer = undefined;
    this.cleanup(connection, disconnectTransport);
    this.update({ status: 'error', recoverableConnectionError: false, error: error instanceof Error ? error.message : String(error) });
  }

  private recover(connection: Connection, error: unknown, peerDisconnected = false): void {
    if (!this.current(connection)) return;
    const session = connection.session;
    if (!session.established || (session.attempts >= IDLE_RETRIES && this.workoutDeviceId === null)) {
      this.failed(connection, error); return;
    }
    const stable = connection.stableSince !== undefined && this.now() - connection.stableSince >= STABLE_SECONDS;
    this.generation += 1; this.connection = undefined;
    this.cleanup(connection, !peerDisconnected);
    session.attempts += 1;
    this.update({ status: 'reconnecting', recoverableConnectionError: true,
      error: error instanceof Error ? error.message : String(error) });
    // Match native recovery: quick recovery after a stable peer drop, then bounded backoff.
    const delay = session.attempts === 1 && peerDisconnected && stable ? 0
      : session.attempts > IDLE_RETRIES ? 30 : 2 ** (session.attempts - 1);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      if (this.session === session) void this.open(session, true);
    }, delay * 1000);
  }

  private async setupStep<T>(connection: Connection, operation: () => Promise<T>, timeoutMessage: string): Promise<T> {
    const deadline = this.now() + SETUP_SECONDS;
    let rejectWait!: (error: Error) => void;
    const interrupted = new Promise<never>((_resolve, reject) => { rejectWait = reject; });
    const cancel = () => rejectWait(new Error('Connection ended'));
    connection.cancelSetup = cancel;
    const timer = setTimeout(() => rejectWait(new Error(timeoutMessage)), SETUP_SECONDS * 1000);
    try {
      const result = await Promise.race([operation(), interrupted]);
      if (!this.current(connection)) throw new Error('Connection ended');
      if (this.now() > deadline) throw new Error(timeoutMessage);
      return result;
    } finally {
      clearTimeout(timer);
      if (connection.cancelSetup === cancel) connection.cancelSetup = undefined;
    }
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
    if (this.session) throw new Error('Disconnect before choosing another bike.');
    if (typeof navigator === 'undefined' || !navigator.bluetooth) throw new Error('Web Bluetooth is unavailable. Use Chrome on Android, supported desktop Chrome / Edge, or the iPhone app.');
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
    clearTimeout(this.retryTimer); this.retryTimer = undefined;
    const previous = this.connection; this.connection = undefined;
    this.generation += 1;
    if (previous) this.cleanup(previous, true);
    const session: BikeSession = { device: this.device, sequence: 0, attempts: 0, established: false };
    this.session = session;
    await this.open(session, false);
  }

  private async open(session: BikeSession, recovering: boolean): Promise<void> {
    if (this.session !== session) return;
    const device = session.device;
    const connection: Connection = {
      generation: ++this.generation, session, decoder: new FrameDecoder(),
      onValue: event => this.receive(connection, event),
      onDisconnect: () => {
        // A queued disconnect event from a superseded attempt can arrive after this GATT has reconnected.
        if (device.gatt?.connected) return;
        this.recover(connection, new Error('Bike disconnected.'), true);
      },
    };
    this.connection = connection;
    device.addEventListener('gattserverdisconnected', connection.onDisconnect);
    if (!recovering) this.update({ status: 'connecting', error: undefined, recoverableConnectionError: false,
      deviceId: device.id, deviceName: device.name, controllerModel: undefined, firmwareLabel: undefined });
    let transportFailure = true;
    try {
      const gatt = device.gatt;
      if (!gatt) throw new Error('Bluetooth GATT is unavailable');
      const server = await this.setupStep(connection, () => gatt.connect().then(server => {
        // A timed-out browser connect may still finish. Never close a newer attempt on the same device.
        if (!this.current(connection) && this.connection?.session.device !== device) server.disconnect();
        return server;
      }), 'Bluetooth connection timed out. Disconnect other bike apps and try again.');
      const service = await this.setupStep(connection, () => server.getPrimaryService(UART_SERVICE),
        'Bike service discovery timed out. Restart the bike and try again.');
      const writer = await this.setupStep(connection, () => service.getCharacteristic(UART_WRITE),
        'Bike connection setup timed out. Restart the bike and try again.');
      connection.writer = writer;
      const reader = await this.setupStep(connection, () => service.getCharacteristic(UART_NOTIFY),
        'Bike connection setup timed out. Restart the bike and try again.');
      connection.reader = reader;
      reader.addEventListener('characteristicvaluechanged', connection.onValue);
      await this.setupStep(connection, () => reader.startNotifications(),
        'Bike notifications timed out. Reconnect and try again.');
      const identity = await this.request(connection, 'identity');
      if (!this.current(connection)) return;
      transportFailure = false;
      const controller = identifyController(identity);
      this.knownControllers.set(device.id, controller.identity);
      this.discovered({ id: device.id, name: device.name ?? 'CYC bike', rssi: 0,
        controllerModel: controller.identity.controllerModel, firmwareLabel: controller.identity.firmwareLabel });
      const epoch = session.epoch ??= this.now();
      session.established = true;
      this.update({ status: recovering ? 'reconnecting' : 'connected', deviceId: device.id, deviceName: device.name ?? 'CYC bike',
        controllerModel: controller.identity.controllerModel, firmwareLabel: controller.identity.firmwareLabel });
      const poll = async (): Promise<void> => {
        if (!this.current(connection)) return;
        const start = this.now();
        let payload: Uint8Array;
        try { payload = await this.request(connection, 'selective'); }
        catch (error) { this.recover(connection, error); return; }
        if (!this.current(connection)) return;
        try {
          const values = controller.adapter.decodeTelemetry(payload);
          const now = this.now();
          connection.stableSince ??= now;
          if (now - connection.stableSince >= STABLE_SECONDS) session.attempts = 0;
          if (this.state.status !== 'connected') this.update({ status: 'connected', error: undefined, recoverableConnectionError: false });
          this.publish(toTelemetrySample(values, { timestamp: new Date().toISOString(), elapsedSeconds: now - epoch, sequence: session.sequence++ }, controller.identity));
          if (!this.current(connection)) return;
          connection.timer = setTimeout(() => { void poll(); }, Math.max(0, 1000 / this.hz - (this.now() - start) * 1000));
        } catch (error) { this.failed(connection, error); }
      };
      void poll();
    } catch (error) {
      if (!this.current(connection)) return;
      if (recovering) {
        if (transportFailure) this.recover(connection, error);
        else this.failed(connection, error);
        return;
      }
      this.failed(connection, error); throw error;
    }
  }

  async disconnect(): Promise<void> {
    this.generation += 1;
    this.session = undefined;
    clearTimeout(this.retryTimer); this.retryTimer = undefined;
    const connection = this.connection; this.connection = undefined;
    if (connection) this.cleanup(connection, true);
    this.update({ status: 'idle', deviceName: undefined, deviceId: undefined, controllerModel: undefined, firmwareLabel: undefined,
      error: undefined, recoverableConnectionError: false });
  }
}
export const deviceAdapter = new BrowserAdapter();
