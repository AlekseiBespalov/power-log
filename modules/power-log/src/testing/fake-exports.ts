import type { FitPowerSource } from '../../../../shared/generated/ts/api';
import type { Result } from '../client';
import type { DiagnosticsEntries, ExportEntries, Shared } from '../exports/entries';

type Answer<T> = Result<T> | Promise<Result<T>>;

export interface FakeExportCall {
  entry: 'shareRide' | 'shareImported' | 'share' | 'shareTrace' | 'purgeTrace';
  args: unknown[];
  signal: AbortSignal | null;
}

export class FakeExportEntries implements ExportEntries, DiagnosticsEntries {
  readonly calls: FakeExportCall[] = [];
  private readonly replies = new Map<FakeExportCall['entry'], (...args: unknown[]) => Answer<unknown>>();

  answer(entry: FakeExportCall['entry'], reply: (...args: unknown[]) => Answer<unknown>): void {
    this.replies.set(entry, reply);
  }

  shareRide(rideId: string, kind: 'fit' | 'csv' | 'zip', fitPower: FitPowerSource, signal: AbortSignal) {
    return this.run<Shared>('shareRide', [rideId, kind, fitPower], signal);
  }

  shareImported(importId: string, name: string, signal: AbortSignal) {
    return this.run<Shared>('shareImported', [importId, name], signal);
  }

  share() {
    return this.run<Shared>('share', [], null);
  }

  shareTrace() {
    return this.run<Shared>('shareTrace', [], null);
  }

  purgeTrace() {
    return this.run<void>('purgeTrace', [], null, { ok: true, value: undefined });
  }

  private async run<T>(
    entry: FakeExportCall['entry'],
    args: unknown[],
    signal: AbortSignal | null,
    unscripted: Result<T> = { ok: true, value: 'dismissed' as T },
  ): Promise<Result<T>> {
    this.calls.push({ entry, args, signal });
    const reply = this.replies.get(entry);
    return (reply ? await reply(...args, signal) : unscripted) as Result<T>;
  }
}
