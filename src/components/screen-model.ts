import {
  kit,
  type ApiFailure,
  type DiagnosticsEntries,
  type ExportEntries,
  type FailureCode,
  type Platform,
  type PowerLogClient,
  type RefusalDetail,
  type Result,
} from '../../modules/power-log';
import type { ShellModel } from '../features/shell/shell-model';
import type { UiPlatform } from './ui-platform';

export interface ScreenModel<S> {
  getSnapshot(): S;
  subscribe(onChange: () => void): () => void;
  activate(): void;
  deactivate(): void;
  dispose(): void;
}

export interface UiEnv {
  client: PowerLogClient;
  ui: UiPlatform;
  shell: ShellModel;
  exports: ExportEntries;
  diagnostics: DiagnosticsEntries;
}

export interface Observable<T> {
  current(): T;
  subscribe(listener: (value: T) => void): () => void;
}

export type NoticeAction = 'dismiss' | 'retry' | 'openAppSettings' | 'reviewRide' | 'resetSettings' | 'refreshStatus';

export interface Notice {
  id: string;
  code: FailureCode;
  text: string;
  actions: readonly [NoticeAction, ...NoticeAction[]];
}

export type ControlState =
  | { label: string; busy: boolean; enabled: true; reason: null }
  | { label: string; busy: boolean; enabled: false; reason: string };

export interface Chip {
  text: string;
  tone: 'accent' | 'warning' | 'neutral';
}

export function run<T>(
  inFlight: Set<string>,
  key: string,
  call: () => Promise<Result<T>>,
  done: (answer: Result<T>) => void,
): void {
  if (inFlight.has(key)) return;
  inFlight.add(key);
  void call().then(answer => {
    inFlight.delete(key);
    done(answer);
  });
}

let localNotices = 0;

export function notice(failure: ApiFailure, text: string, actions: Notice['actions']): Notice {
  return { id: failure.occurrence ?? `local:${++localNotices}`, code: failure.code, text, actions };
}

export function failureText(
  display: { platform: Platform; sdkLevel: number | null },
  code: FailureCode,
  detail: RefusalDetail | null = null,
): string {
  return kit.textsFailure(code, detail, display.platform, display.sdkLevel).text;
}

export const shellFailure = (code: FailureCode) => code === 'storageRefused' || code === 'apiMismatch';

export function streamNotice(failure: ApiFailure, text: string): Notice | null {
  if (failure.code === 'storageBusy') return null;
  return notice(failure, text, failure.retryable ? ['dismiss'] : ['retry']);
}

export class StreamFailures {
  private readonly notices = new Map<string, Notice>();

  failed(stream: string, notice: Notice | null): void {
    if (notice) this.notices.set(stream, notice);
    else this.notices.delete(stream);
  }

  answered(stream: string): void {
    this.notices.delete(stream);
  }

  has(stream: string, code: FailureCode): boolean {
    return this.notices.get(stream)?.code === code;
  }

  dismiss(id: string): void {
    for (const [stream, notice] of this.notices) if (notice.id === id) this.notices.delete(stream);
  }

  clear(): void {
    this.notices.clear();
  }

  shown(dismissed: (id: string) => boolean): Notice | null {
    for (const notice of this.notices.values()) if (!dismissed(notice.id)) return notice;
    return null;
  }
}

export class NoticeSlot {
  private failure: string | null = null;
  private current: Notice | null = null;

  take(failure: ApiFailure | null, make: (failure: ApiFailure) => Notice): Notice | null {
    const key = failure === null ? null : JSON.stringify(failure);
    if (key !== this.failure) {
      this.failure = key;
      this.current = failure === null ? null : make(failure);
    }
    return this.current;
  }
}

export function control(label: string, busy: boolean, reason: string | null): ControlState {
  return reason === null ? { label, busy, enabled: true, reason: null } : { label, busy, enabled: false, reason };
}

export function shared<T>(previous: T, next: T): T {
  if (previous === next) return previous;
  if (ArrayBuffer.isView(previous) || ArrayBuffer.isView(next)) return next;
  if (typeof previous !== 'object' || typeof next !== 'object' || previous === null || next === null) return next;
  if (Array.isArray(previous) !== Array.isArray(next)) return next;
  const before = previous as Record<string, unknown>;
  const after = next as Record<string, unknown>;
  const keys = Object.keys(after);
  if (keys.length !== Object.keys(before).length) return next;
  const merged: Record<string, unknown> = Array.isArray(next) ? ([] as unknown as Record<string, unknown>) : {};
  let same = true;
  for (const key of keys) {
    merged[key] = shared(before[key], after[key]);
    if (merged[key] !== before[key]) same = false;
  }
  return same ? previous : (merged as T);
}

export class Snapshots<S> {
  private readonly listeners = new Set<() => void>();

  constructor(private snapshot: S) {}

  get(): S {
    return this.snapshot;
  }

  set(next: S): void {
    const merged = shared(this.snapshot, next);
    if (merged === this.snapshot) return;
    this.snapshot = merged;
    for (const listener of [...this.listeners]) listener();
  }

  subscribe(onChange: () => void): () => void {
    this.listeners.add(onChange);
    return () => {
      this.listeners.delete(onChange);
    };
  }
}
