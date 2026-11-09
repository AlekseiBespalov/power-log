import { startWebRuntime, type WebSession } from '../../shared/generated/js/web';
import { createPowerLogClient, type PowerLogClient } from '../../modules/power-log/src/client';
import { exportFile } from '../../modules/power-log/src/exports/export-file';
import { openSink } from '../../modules/power-log/src/exports/sink.web';
import type { BinaryPage } from '../../modules/power-log/src/transport';

const clients = new Map<string, PowerLogClient>();

function client(archiveName: string): PowerLogClient {
  let found = clients.get(archiveName);
  if (!found) {
    const session: WebSession = startWebRuntime(archiveName, '0.0.0');
    found = createPowerLogClient({
      call: (op, request) => new Promise(resolve => session.call(op, request, resolve)),
      next: (stream, request) => new Promise(resolve => session.next(stream, request, resolve)),
      exportPage: request => new Promise<BinaryPage>(resolve => session.exportPage(request, resolve)),
    });
    clients.set(archiveName, found);
  }
  return found;
}

export function exportRide(archiveName: string, rideId: string, kind: 'zip' | 'fit' | 'csv') {
  return exportFile(
    client(archiveName),
    { source: { kind: 'ride', rideId }, kind, fitPower: 'rider', exportedAt: '2026-11-08T12:00:00.000Z' },
    openSink,
  );
}
