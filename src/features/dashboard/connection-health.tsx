import { useEffect, useState } from 'react';
import { MAX_SAMPLE_GAP_SECONDS, type ConnectionDiagnostics } from '../../core/types';
import type { TelemetryAdapter } from '../../services/adapter';
import { Body } from '../../components/ui';

export function ConnectionHealth({ adapter, readingsAvailable }: { adapter: TelemetryAdapter; readingsAvailable: boolean }) {
  const [snapshot, setSnapshot] = useState<{ adapter: TelemetryAdapter; data: ConnectionDiagnostics; lastReceivedHz: number | null } | null>(null);
  useEffect(() => {
    const read = adapter.getDiagnostics;
    let disposed = false;
    let pending = false;
    if (!read) return;
    const refresh = async () => {
      if (pending) return;
      pending = true;
      try {
        const next = await read();
        if (!disposed) setSnapshot(previous => {
          const fresh = next.status === 'connected' && next.lastSampleAgeSeconds !== null
            && next.lastSampleAgeSeconds >= 0 && next.lastSampleAgeSeconds <= MAX_SAMPLE_GAP_SECONDS;
          const measuredRate = fresh && next.recentSampleHz !== null && Number.isFinite(next.recentSampleHz)
            ? next.recentSampleHz : null;
          return { adapter, data: next, lastReceivedHz: measuredRate ?? (previous?.adapter === adapter ? previous.lastReceivedHz : null) };
        });
      } catch {
        if (!disposed) setSnapshot(null);
      } finally { pending = false; }
    };
    void refresh();
    // This only refreshes the UI. Native capture and diagnostics do not depend on it.
    const timer = setInterval(() => { void refresh(); }, 5000);
    return () => { disposed = true; clearInterval(timer); };
  }, [adapter]);
  const diagnostics = snapshot?.adapter === adapter ? snapshot.data : null;
  if (!diagnostics || diagnostics.connectionAttempts === 0) return null;
  const measuredRate = snapshot?.lastReceivedHz;
  const rate = readingsAvailable ? measuredRate != null ? `${measuredRate.toFixed(1)} Hz received` : 'Connected' : 'Waiting for fresh readings';
  return <Body muted>Requested {diagnostics.requestedHz} Hz · {rate}{'\n'}
    {diagnostics.reconnects} reconnect{diagnostics.reconnects === 1 ? '' : 's'}
    {diagnostics.lastGapSeconds !== null ? `\nLast data gap: ${diagnostics.lastGapSeconds.toFixed(1)} s` : ''}
  </Body>;
}
