import {
  BluetoothTrace,
  controllerProtocol,
  TelemetryDiscoveryWindow,
  TelemetryTransportSession,
  telemetryRecoveryPlan,
  telemetryReplyAdmissible,
  telemetryTimingPolicy,
  telemetryWriteExpired,
} from '../../shared/js';
import type { ControllerIdentity, ControllerResponse } from '../../shared/js/power-log-core';
import { telemetrySample } from '../core/telemetry';
import { sampleRates } from '../core/workouts';
import type { ConnectionOptions, Device, NativeState, TelemetrySample } from '../core/types';
import { idleState, type AdapterEvents, type TelemetryAdapter } from './adapter';
import { UART_NOTIFY, UART_SERVICE, UART_WRITE } from './controller-gatt';
import { failurePresentation } from '../core/failures';
import { browserFailure, browserFailureCode } from './browser-failures';

type RequestRole = 'identity' | 'telemetry';
type ReceivedResponse = { reply: ControllerResponse; acquiredAt: number; timestamp: string };
interface PendingResponse {
  kind: RequestRole;
  sentAt: number;
  received: boolean;
  written: boolean;
  writtenAt?: number;
  resolve: (response: ReceivedResponse) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
}
interface BikeSession {
  device: BluetoothDevice;
  epoch: number;
  sequence: number;
  attempts: number;
}
interface Connection {
  generation: string;
  session: BikeSession;
  writer?: BluetoothRemoteGATTCharacteristic;
  reader?: BluetoothRemoteGATTCharacteristic;
  timer?: ReturnType<typeof setTimeout>;
  attemptTimer?: ReturnType<typeof setTimeout>;
  cancelSetup?: () => void;
  pending?: PendingResponse;
  operations: number;
  cancelling: boolean;
  disconnectCompleted: boolean;
  disconnectCompletions: number;
  onValue: EventListener;
  onDisconnect: EventListener;
  stableSince?: number;
}
const timing = telemetryTimingPolicy();
const MAX_NOTIFICATION_BYTES = 65536;
class ConnectionError extends Error {}
class RejectedReplyError extends Error {}
const timeoutError = () => new ConnectionError('Controller response timed out');
const cancellationError = () => new ConnectionError('Bluetooth cancellation timed out. Disconnect and try again.');
const attemptTimeoutError = () =>
  new ConnectionError('Bluetooth connection timed out. Disconnect other bike apps and try again.');
const connectionErrorText = (error: unknown) =>
  error instanceof ConnectionError || error instanceof RejectedReplyError
    ? error.message
    : failurePresentation(browserFailureCode(error, 'bluetooth')).text;

export class BrowserAdapter implements TelemetryAdapter {
  readonly kind = 'web' as const;
  private readonly bluetoothTrace = new BluetoothTrace();
  readonly bluetoothDiagnostics = {
    getBluetoothTrace: async () => {
      const state = this.bluetoothTrace.status(this.now());
      return {
        capturing: state.capturing,
        eventCount: state.eventCount,
        byteCount: state.byteCount,
        stopReason: state.stopReason ?? null,
        lastEvent: state.lastEvent ?? null,
      };
    },
    startBluetoothTrace: async () => {
      this.bluetoothTrace.start(this.now());
      this.trace('capture_started', `platform=web; status=${this.state.status}; requests=111,50`);
    },
    stopBluetoothTrace: async () => this.bluetoothTrace.stop(),
    clearBluetoothTrace: async () => this.bluetoothTrace.clear(),
    readBluetoothTrace: async () => this.bluetoothTrace.exportJson(this.now()),
  };
  private trace(event: string, detail: string, bytes: Int8Array | null = null) {
    this.bluetoothTrace.record(this.now(), event, detail, bytes);
  }
  private state = idleState();
  private connectionEpoch?: string;
  private listeners = new Set<AdapterEvents>();
  private workoutDeviceId: string | null = null;
  private device?: BluetoothDevice;
  private session?: BikeSession;
  private connection?: Connection;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private scanTimer?: ReturnType<typeof setTimeout>;
  private scan?: TelemetryDiscoveryWindow;
  private scanGeneration = 0n;
  private admissionGeneration = 0n;
  private readonly transport = new TelemetryTransportSession(controllerProtocol());
  private hz = 2;
  private knownControllers = new Map<string, ControllerIdentity>();

  constructor(private readonly now = () => performance.now() / 1000) {}

  subscribe(events: AdapterEvents) {
    this.listeners.add(events);
    return () => {
      this.listeners.delete(events);
    };
  }
  async getState() {
    return { ...this.state };
  }
  private requireWorkoutDevice(deviceId: string): void {
    if (this.workoutDeviceId !== null && this.workoutDeviceId !== deviceId)
      throw new Error('Finish the ride before changing its bike.');
  }
  private update(patch: Partial<NativeState>) {
    if (patch.status || patch.error) this.trace('state', `${patch.status ?? this.state.status}; ${patch.error ?? ''}`);
    if (patch.status === 'connected' && this.state.status !== 'connected') this.connectionEpoch = crypto.randomUUID();
    this.state = { ...this.state, ...patch };
    this.listeners.forEach(listener => listener.state({ ...this.state }));
  }
  private discovered(device: Device) {
    this.trace('discovered', `name=${device.name}; rssi=${device.rssi}`);
    this.listeners.forEach(listener => listener.device(device));
  }
  private publish(sample: TelemetrySample, acquiredAt: number) {
    if (this.connectionEpoch) sample = { ...sample, connectionEpoch: this.connectionEpoch };
    this.listeners.forEach(listener => listener.sample(sample, { receivedAtSeconds: acquiredAt }));
  }
  setWorkoutOwner(deviceId: string | null): void {
    const previous = this.workoutDeviceId;
    if (deviceId !== null) this.requireWorkoutDevice(deviceId);
    this.workoutDeviceId = deviceId;
    if (previous !== null && deviceId === null && this.state.status === 'reconnecting') void this.disconnect();
  }
  async setSampleRate(hz: number): Promise<void> {
    if (!sampleRates.includes(hz)) throw new Error('Choose 2, 4 or 8 Hz');
    if (this.workoutDeviceId !== null && hz !== this.hz)
      throw new Error('Finish the ride before changing its sample rate');
    this.hz = hz;
  }
  private current(connection: Connection): boolean {
    return (
      this.connection === connection && this.transport.generation === connection.generation && !connection.cancelling
    );
  }

  private finishCancellation(connection: Connection): void {
    if (
      !connection.cancelling ||
      connection.operations !== 0 ||
      !connection.disconnectCompleted ||
      connection.disconnectCompletions !== 0
    )
      return;
    this.transport.disconnected(connection.generation, this.now());
    connection.session.device.removeEventListener('gattserverdisconnected', connection.onDisconnect);
    if (this.transport.cancellationFailed) {
      this.session = undefined;
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
      this.update({ status: 'error', recoverableConnectionError: false, error: cancellationError().message });
    } else if (this.session && this.transport.reconnectDue !== null) this.waitForRetry(this.session);
  }

  private cancelGatt(connection: Connection): void {
    const connected = Boolean(connection.session.device.gatt?.connected);
    connection.disconnectCompleted = !connected && connection.disconnectCompletions === 0;
    if (connected) connection.disconnectCompletions += 1;
    connection.session.device.gatt?.disconnect();
  }

  private cleanup(connection: Connection): void {
    clearTimeout(connection.timer);
    clearTimeout(connection.attemptTimer);
    connection.cancelSetup?.();
    connection.cancelSetup = undefined;
    const pending = connection.pending;
    connection.pending = undefined;
    if (pending) {
      clearTimeout(pending.timeout);
      pending.reject(new ConnectionError('Connection ended'));
    }
    connection.reader?.removeEventListener('characteristicvaluechanged', connection.onValue);
    connection.reader = undefined;
    connection.writer = undefined;
    connection.cancelling = true;
    if (!connection.disconnectCompleted || connection.session.device.gatt?.connected) this.cancelGatt(connection);
    this.finishCancellation(connection);
  }

  private failed(connection: Connection, error: unknown): void {
    if (this.connection !== connection) return;
    this.connection = undefined;
    this.session = undefined;
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    this.transport.manualDisconnect(connection.operations > 0 || !connection.disconnectCompleted);
    this.cleanup(connection);
    this.update({ status: 'error', recoverableConnectionError: false, error: connectionErrorText(error) });
  }

  private recover(connection: Connection, error: unknown, peerDisconnected = false): void {
    if (this.connection !== connection || connection.cancelling) return;
    const session = connection.session;
    const disconnected = connection.disconnectCompleted && connection.operations === 0;
    const plan = telemetryRecoveryPlan(
      session.attempts + 1,
      this.workoutDeviceId !== null,
      connection.disconnectCompleted,
      peerDisconnected,
      connection.stableSince === undefined ? null : this.now() - connection.stableSince,
      false,
      connection.operations > 0,
      disconnected,
    );
    if (!plan) {
      this.failed(connection, error);
      return;
    }
    this.connection = undefined;
    session.attempts += 1;
    this.transport.scheduleRetry(plan, this.now());
    this.update({ status: 'reconnecting', recoverableConnectionError: true, error: connectionErrorText(error) });
    this.cleanup(connection);
    this.waitForRetry(session);
  }

  private waitForRetry(session: BikeSession): void {
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    const due = this.transport.reconnectDue;
    if (this.session !== session || due == null) return;
    const now = this.now();
    const deadline = this.transport.cancellationPending ? this.transport.cancellationDeadline! : due;
    this.retryTimer = setTimeout(
      () => {
        this.retryTimer = undefined;
        if (this.session !== session) return;
        const action = this.transport.check(this.now(), null, null);
        if (action === 'cancellationTimeout') {
          this.session = undefined;
          this.update({ status: 'error', recoverableConnectionError: false, error: cancellationError().message });
        } else if (action === 'beginRetry') void this.open(session, true);
        else this.waitForRetry(session);
      },
      Math.max(0, deadline - now) * 1000,
    );
  }

  private async operation<T>(connection: Connection, work: () => Promise<T>): Promise<T> {
    connection.operations += 1;
    try {
      return await work();
    } finally {
      connection.operations -= 1;
      if (connection.cancelling && connection.session.device.gatt?.connected) {
        if (this.transport.reconnectDue != null) this.transport.cancelDuringBackoff();
        this.cancelGatt(connection);
      }
      this.finishCancellation(connection);
    }
  }

  private async setupStep<T>(connection: Connection, operation: () => Promise<T>): Promise<T> {
    let rejectWait!: (error: Error) => void;
    const interrupted = new Promise<never>((_resolve, reject) => {
      rejectWait = reject;
    });
    const cancel = () => rejectWait(new ConnectionError('Connection ended'));
    connection.cancelSetup = cancel;
    try {
      const result = await Promise.race([this.operation(connection, operation), interrupted]);
      if (!this.current(connection)) throw new ConnectionError('Connection ended');
      if (this.transport.check(this.now(), null, null) === 'attemptTimeout') throw attemptTimeoutError();
      return result;
    } finally {
      if (connection.cancelSetup === cancel) connection.cancelSetup = undefined;
    }
  }

  private advance(connection: Connection, expected: string, next: string): void {
    const action = this.transport.advance(connection.generation, expected, next, this.now());
    if (action === 'attemptTimeout') throw attemptTimeoutError();
    if (action !== 'advanced') throw new ConnectionError('Connection ended');
  }

  private receive(connection: Connection, event: Event): void {
    if (!this.current(connection) || event.target !== connection.reader) return;
    const pending = connection.pending;
    const view = connection.reader?.value;
    if (!view) return;
    this.trace(
      'notification',
      `characteristic=${UART_NOTIFY}; request=${pending?.kind ?? 'none'}`,
      new Int8Array(view.buffer, view.byteOffset, view.byteLength),
    );
    if (!pending) return;
    if (view.byteLength > MAX_NOTIFICATION_BYTES) {
      this.failed(connection, new ConnectionError('Bike reply exceeds the supported size.'));
      return;
    }
    const acquiredAt = this.now();
    const timestamp = new Date().toISOString();
    const decision = this.transport.notifications(
      connection.generation,
      pending.kind,
      new Int8Array(view.buffer, view.byteOffset, view.byteLength),
      pending.sentAt,
      acquiredAt,
    );
    this.trace(
      'decoder',
      `action=${decision.action}; discardedBytes=${decision.discardedBytes}; pendingBytes=${decision.pendingBytes}`,
    );
    for (const reply of decision.replies) {
      if (reply.rejectionReason) this.trace('rejected', `request=${reply.kind}; reason=${reply.rejectionReason}`);
    }
    if (decision.action === 'replyAccepted') {
      const reply = decision.replies[0]!;
      pending.received = true;
      pending.resolve({ reply, acquiredAt, timestamp });
    } else if (decision.action === 'replyRejected') {
      const reply = decision.replies[0]!;
      pending.reject(
        new RejectedReplyError(
          reply.kind === 'identity'
            ? 'Unsupported controller. Connect a CYC X6 or X12.'
            : reply.rejectionReason === 'telemetryMask'
              ? 'Unexpected telemetry field mask.'
              : 'Telemetry command or length does not match the verified profile.',
        ),
      );
    } else if (decision.action !== 'none') pending.reject(timeoutError());
  }

  async startScan(): Promise<void> {
    if (this.workoutDeviceId !== null) throw new Error('Finish the ride before choosing another bike.');
    if (this.session || this.transport.cancellationPending) throw new Error('Disconnect before choosing another bike.');
    if (typeof navigator === 'undefined' || !navigator.bluetooth) {
      this.update({
        status: 'error',
        recoverableConnectionError: false,
        error:
          'Web Bluetooth is unavailable. Use Chrome on Android, supported desktop Chrome / Edge, or the iPhone app.',
      });
      return;
    }
    void this.stopScan();
    const generation = (++this.scanGeneration).toString();
    const window = new TelemetryDiscoveryWindow(generation, this.now());
    this.scan = window;
    this.update({ status: 'scanning', error: undefined });
    this.scanTimer = setTimeout(
      () => {
        if (this.scan === window && window.expired(this.now())) void this.stopScan();
      },
      (window.deadline - this.now()) * 1000,
    );
    try {
      const device = await navigator.bluetooth.requestDevice({
        filters: [{ services: [UART_SERVICE] }, { namePrefix: 'CYCMOTOR' }],
        optionalServices: [UART_SERVICE],
      });
      // Web Bluetooth cannot close its chooser, so a choice made after this scan stopped is ignored.
      if (this.scan !== window) return;
      if (!window.accepts(this.scanGeneration.toString(), this.now())) {
        await this.stopScan();
        return;
      }
      this.device = device;
      const controller = this.knownControllers.get(device.id);
      this.discovered({
        id: device.id,
        name: device.name ?? 'CYC controller',
        rssi: 0,
        ...(controller ? { controllerModel: controller.controllerModel, firmwareLabel: controller.firmwareLabel } : {}),
      });
      await this.stopScan();
    } catch (error) {
      if (this.scan !== window) return;
      await this.stopScan();
      if (error instanceof DOMException && error.name === 'NotFoundError') return;
      throw browserFailure(error, 'bluetooth');
    }
  }

  async stopScan(): Promise<void> {
    if (!this.scan) return;
    this.scan = undefined;
    this.scanGeneration += 1n;
    clearTimeout(this.scanTimer);
    this.scanTimer = undefined;
    this.update({ status: 'idle' });
  }

  private async request(
    connection: Connection,
    kind: RequestRole,
    onResponse?: (response: ReceivedResponse) => void,
  ): Promise<ReceivedResponse> {
    const writer = connection.writer;
    if (!this.current(connection) || !writer || connection.pending) throw new Error('Bluetooth transport is not ready');
    let resolveResponse!: PendingResponse['resolve'];
    let rejectResponse!: PendingResponse['reject'];
    let rejectDeadline!: PendingResponse['reject'];
    const response = new Promise<ReceivedResponse>((resolve, reject) => {
      resolveResponse = resolve;
      rejectResponse = reject;
    });
    const deadline = new Promise<never>((_resolve, reject) => {
      rejectDeadline = reject;
    });
    const sentAt = this.now();
    const pending: PendingResponse = {
      kind,
      sentAt,
      received: false,
      written: false,
      resolve: response => {
        onResponse?.(response);
        resolveResponse(response);
      },
      reject: error => {
        rejectResponse(error);
        rejectDeadline(error);
      },
      timeout: setTimeout(() => {
        if (connection.pending !== pending) return;
        const action = this.transport.check(
          this.now(),
          pending.received ? null : sentAt,
          pending.written ? null : sentAt,
        );
        if (action !== 'none') pending.reject(timeoutError());
      }, timing.telemetryTimeoutSeconds * 1000),
    };
    connection.pending = pending;
    void response.catch(() => {});
    void deadline.catch(() => {});
    try {
      const bytes = new Uint8Array(this.transport.requestFrame(kind));
      this.trace(
        'write',
        `request=${kind}; characteristic=${UART_WRITE}; withoutResponse=${writer.properties.writeWithoutResponse}`,
        new Int8Array(bytes),
      );
      const write = this.operation(connection, () =>
        writer.properties.writeWithoutResponse
          ? writer.writeValueWithoutResponse(bytes)
          : writer.writeValueWithResponse(bytes),
      ).then(() => {
        pending.written = true;
        pending.writtenAt = this.now();
      });
      const [, result] = await Promise.race([Promise.all([write, response]), deadline]);
      if (!this.current(connection)) throw new ConnectionError('Connection ended');
      if (!telemetryReplyAdmissible(sentAt, this.now()) || telemetryWriteExpired(sentAt, pending.writtenAt!))
        throw timeoutError();
      return result;
    } finally {
      if (connection.pending === pending) connection.pending = undefined;
      clearTimeout(pending.timeout);
    }
  }

  async connect({ deviceId, hz }: ConnectionOptions): Promise<void> {
    this.requireWorkoutDevice(deviceId);
    if (!sampleRates.includes(hz)) throw new Error('Choose 2, 4 or 8 Hz');
    if (!this.device || this.device.id !== deviceId || this.scan) throw new Error('Choose the bike first');
    const admission = ++this.admissionGeneration;
    const device = this.device;
    if (this.connection || this.session || this.transport.cancellationFailed) await this.endConnection();
    else if (this.transport.cancellationPending) await Promise.resolve();
    if (admission !== this.admissionGeneration) return;
    if (this.transport.cancellationPending) throw new Error('Bluetooth cancellation is still pending');
    if (this.device !== device || this.scan) throw new Error('Choose the bike first');
    if (this.workoutDeviceId === null) this.hz = hz;
    const session: BikeSession = { device, epoch: this.now(), sequence: 0, attempts: 0 };
    this.session = session;
    void this.open(session, false);
  }

  private async open(session: BikeSession, recovering: boolean): Promise<void> {
    if (this.session !== session || !this.transport.begin(this.now(), false)) return;
    const device = session.device;
    const connection: Connection = {
      generation: this.transport.generation,
      session,
      operations: 0,
      cancelling: false,
      disconnectCompleted: true,
      disconnectCompletions: 0,
      onValue: event => this.receive(connection, event),
      onDisconnect: () => {
        if (connection.cancelling) {
          if (connection.disconnectCompletions > 0) connection.disconnectCompletions -= 1;
          connection.disconnectCompleted = true;
          if (device.gatt?.connected && connection.operations === 0) this.cancelGatt(connection);
          else this.finishCancellation(connection);
        } else if (!device.gatt?.connected) {
          connection.disconnectCompleted = true;
          this.recover(connection, new ConnectionError('Bike disconnected.'), true);
        }
      },
    };
    this.connection = connection;
    device.addEventListener('gattserverdisconnected', connection.onDisconnect);
    connection.attemptTimer = setTimeout(
      () => {
        if (!this.current(connection)) return;
        if (this.transport.check(this.now(), null, null) === 'attemptTimeout')
          this.recover(connection, attemptTimeoutError());
      },
      (this.transport.attemptDeadline! - this.now()) * 1000,
    );
    if (!recovering)
      this.update({
        status: 'connecting',
        error: undefined,
        recoverableConnectionError: false,
        deviceId: device.id,
        deviceName: device.name,
        controllerModel: undefined,
        firmwareLabel: undefined,
      });
    try {
      const gatt = device.gatt;
      if (!gatt) throw new ConnectionError('Bluetooth is unavailable in this browser.');
      connection.disconnectCompleted = false;
      const server = await this.setupStep(connection, () =>
        gatt.connect().catch(error => {
          if (!connection.cancelling && !gatt.connected) connection.disconnectCompleted = true;
          throw error;
        }),
      );
      this.advance(connection, 'connecting', 'services');
      const service = await this.setupStep(connection, () => server.getPrimaryService(UART_SERVICE));
      this.trace('service', UART_SERVICE);
      this.advance(connection, 'services', 'characteristics');
      connection.writer = await this.setupStep(connection, () => service.getCharacteristic(UART_WRITE));
      const reader = await this.setupStep(connection, () => service.getCharacteristic(UART_NOTIFY));
      connection.reader = reader;
      for (const characteristic of [connection.writer, reader]) {
        const properties = characteristic.properties;
        this.trace(
          'characteristic',
          `uuid=${characteristic.uuid}; read=${properties.read}; write=${properties.write}; writeWithoutResponse=${properties.writeWithoutResponse}; notify=${properties.notify}; indicate=${properties.indicate}`,
        );
      }
      this.advance(connection, 'characteristics', 'notifications');
      reader.addEventListener('characteristicvaluechanged', connection.onValue);
      await this.setupStep(connection, () => reader.startNotifications());
      this.trace('subscription', `characteristic=${UART_NOTIFY}; enabled=true`);
      this.advance(connection, 'notifications', 'identity');
      const { reply } = await this.request(connection, 'identity');
      this.advance(connection, 'identity', 'ready');
      clearTimeout(connection.attemptTimer);
      const controller = reply.identity!;
      this.knownControllers.set(device.id, controller);
      this.discovered({
        id: device.id,
        name: device.name ?? 'CYC bike',
        rssi: 0,
        controllerModel: controller.controllerModel,
        firmwareLabel: controller.firmwareLabel,
      });
      this.update({
        status: recovering ? 'reconnecting' : 'connected',
        deviceId: device.id,
        deviceName: device.name ?? 'CYC bike',
        controllerModel: controller.controllerModel,
        firmwareLabel: controller.firmwareLabel,
      });
      const poll = async (): Promise<void> => {
        if (!this.current(connection)) return;
        const start = this.now();
        try {
          await this.request(connection, 'telemetry', response => {
            const now = response.acquiredAt;
            connection.stableSince ??= now;
            if (now - connection.stableSince >= timing.stableTelemetrySeconds) session.attempts = 0;
            if (this.state.status !== 'connected')
              this.update({ status: 'connected', error: undefined, recoverableConnectionError: false });
            this.publish(
              telemetrySample(response.reply.reading!, {
                timestamp: response.timestamp,
                elapsedSeconds: now - session.epoch,
                sequence: session.sequence++,
              }),
              now,
            );
          });
        } catch (error) {
          if (error instanceof RejectedReplyError) this.failed(connection, error);
          else this.recover(connection, error);
          return;
        }
        if (!this.current(connection)) return;
        connection.timer = setTimeout(() => void poll(), Math.max(0, 1 / this.hz - (this.now() - start)) * 1000);
      };
      void poll();
    } catch (error) {
      if (this.connection !== connection || connection.cancelling) return;
      if (error instanceof RejectedReplyError || (error instanceof DOMException && error.name === 'NotFoundError'))
        this.failed(connection, error);
      else this.recover(connection, error);
    }
  }

  async disconnect(): Promise<void> {
    this.admissionGeneration += 1n;
    await this.endConnection();
  }

  private async endConnection(): Promise<void> {
    void this.stopScan();
    this.session = undefined;
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    const connection = this.connection;
    this.connection = undefined;
    this.transport.manualDisconnect(
      this.transport.cancellationPending ||
        !!connection?.operations ||
        (!!connection && !connection.disconnectCompleted),
    );
    if (connection) this.cleanup(connection);
    this.update({ status: 'idle', error: undefined, recoverableConnectionError: false });
  }
}
export const deviceAdapter = new BrowserAdapter();
