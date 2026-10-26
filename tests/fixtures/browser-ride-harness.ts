import { writeConsumption } from '../../src/core/export/tables/consumption';
import { writeGps } from '../../src/core/export/tables/gps';
import { ExportReads, createSlicer } from '../../src/core/export/pages';
import { writeTelemetry } from '../../src/core/export/tables/telemetry';
import { TextChunks } from '../../src/core/export/tables/text';
import { readTimeline } from '../../src/core/export/timeline';
import type { TelemetrySample } from '../../src/core/types';
import type { MonitorLatestRequest, MonitorSource } from '../../src/core/monitor';
import { browserExportSource } from '../../src/services/browser-export-source';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import {
  browserRideStore,
  browserDatabase,
  browserTransaction,
  idbRequest,
  BROWSER_BATCH,
} from '../../src/services/browser-ride-store';
import { browserWorkoutMonitorSource } from '../../src/services/browser-workout-monitor';
import { TestTelemetryAdapter } from '../support/telemetry-adapter';
import { syntheticSample } from './synthetic-sample';
export { browserRow, browserRide } from '../support/browser-ride';
export { HistoryCatalog } from '../../src/services/history-catalog';
export { browserStoragePersistence } from '../../src/services/browser-ride-store';
export { metadataOf } from '../../src/services/browser-ride-store';
export { BROWSER_RECORDING_LOCK } from '../../src/services/browser-workout-recorder';
export {
  browserRideStore as store,
  browserDatabase,
  browserTransaction,
  idbRequest,
  BROWSER_BATCH,
  browserWorkoutMonitorSource as monitor,
  syntheticSample,
};
export {
  ensureBrowserDistance,
  retainBrowserDistance,
  peekBrowserDistance,
  browserDistanceCurrent,
  browserDistanceStats,
  browserDistanceIntervals,
  browserDistanceLatest,
  inspectBrowserDistance,
  plotBrowserDistance,
} from '../../src/services/browser-distance-store';

export async function readSettledLatest(source: MonitorSource, request: MonitorLatestRequest) {
  let latest = await source.readLatest(request);
  while (latest.status === 'ok' && latest.averagesPending) {
    await new Promise(resolve => setTimeout(resolve, 5));
    latest = await source.readLatest(request);
  }
  return latest;
}

export function createRecorder() {
  let time = 0;
  const utc = () => new Date(Date.UTC(2026, 0, 1) + time * 1000).toISOString();
  class TestSource extends TestTelemetryAdapter {
    sample(sequence: number, power = 100, extras: Partial<TelemetrySample> = {}) {
      this.emitSample({
        ...syntheticSample(time, sequence, utc()),
        connectionEpoch: this.connectionEpoch,
        humanPowerW: power,
        ...extras,
      });
    }
    changeIdentity() {
      this.emitState({ deviceId: 'another-bike' });
    }
  }
  const source = new TestSource(() => time);
  const recorder = new BrowserWorkoutRecorder(browserRideStore, () => time, utc);
  recorder.setTelemetrySource(source);
  return {
    source,
    recorder,
    setTime: (seconds: number) => {
      time = seconds;
    },
  };
}

export async function telemetryCsv(rideId: string): Promise<string> {
  const context = { exportedAt: '2026-01-01T00:00:00.000Z', platform: 'web' as const };
  const opened = await browserExportSource.open({ rideId, kind: 'zip', context });
  try {
    const reads = new ExportReads({ platform: 'web', kind: 'zip', slicer: createSlicer() });
    const timeline = await readTimeline(browserExportSource, opened.session, opened.elapsedEnd, reads, {
      fitLimits: false,
    });
    const chunks: Uint8Array[] = [];
    const out = new TextChunks({ write: async bytes => void chunks.push(bytes.slice()) });
    await writeTelemetry({ source: browserExportSource, session: opened.session, reads, timeline, out });
    await out.end();
    return chunks.map(chunk => new TextDecoder().decode(chunk)).join('');
  } finally {
    await browserExportSource.close(opened.session);
  }
}

export { browserIndexKey, timestampUs, secondsUs } from '../../src/services/browser-storage-keys';

export { browserExportSource };

export async function gpsCsv(rideId: string): Promise<string> {
  const opened = await browserExportSource.open({
    rideId,
    kind: 'zip',
    context: { platform: 'web', exportedAt: '2026-01-01T00:00:00Z' },
  });
  try {
    const reads = new ExportReads({ platform: 'web', kind: 'zip', slicer: createSlicer() });
    const timeline = await readTimeline(browserExportSource, opened.session, opened.elapsedEnd, reads, {
      fitLimits: false,
    });
    const chunks: Uint8Array[] = [];
    const out = new TextChunks({ write: async bytes => void chunks.push(bytes.slice()) });
    await writeGps(
      { source: browserExportSource, session: opened.session, reads, timeline, out },
      opened.producers.gps,
    );
    await out.end();
    return chunks.map(bytes => new TextDecoder().decode(bytes)).join('');
  } finally {
    await browserExportSource.close(opened.session);
  }
}
export { registerBrowserWorkoutClock } from '../../src/services/browser-workout-monitor';
export { browserStatistics } from '../../src/services/browser-analytics';

export {
  InspectionMetricBucket,
  InspectionMetricQuery,
  InspectionCacheEncoder,
  InspectionOriginalInput,
} from '../../shared/js';

export { InspectionPlotBucket, InspectionPlotQuery } from '../../shared/js';
export { browserInspectionItem } from '../../src/services/browser-analytics';

export async function consumptionCsv(rideId: string): Promise<string> {
  const opened = await browserExportSource.open({
    rideId,
    kind: 'zip',
    context: { platform: 'web', exportedAt: '2026-01-01T00:00:00Z' },
  });
  try {
    const reads = new ExportReads({ platform: 'web', kind: 'zip', slicer: createSlicer() });
    const timeline = await readTimeline(browserExportSource, opened.session, opened.elapsedEnd, reads, {
      fitLimits: false,
    });
    const chunks: Uint8Array[] = [];
    const out = new TextChunks({ write: async bytes => void chunks.push(bytes.slice()) });
    await writeConsumption({ source: browserExportSource, session: opened.session, reads, timeline, out });
    await out.end();
    return chunks.map(bytes => new TextDecoder().decode(bytes)).join('');
  } finally {
    await browserExportSource.close(opened.session);
  }
}
