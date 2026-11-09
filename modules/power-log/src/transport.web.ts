import type { WebSession } from '../../../shared/generated/js/web';
import { version } from '../../../package.json';
import type { BinaryPage, Transport } from './transport';

let session: Promise<WebSession> | undefined;

// The runtime starts once the first render has painted and the page is idle; the timeout covers a page that never
// idles, and Safari has no requestIdleCallback.
const afterFirstPaint = () =>
  new Promise<void>(resolve =>
    typeof requestIdleCallback === 'function'
      ? requestIdleCallback(() => resolve(), { timeout: 1000 })
      : setTimeout(resolve, 0),
  );

const started = () =>
  (session ??= afterFirstPaint()
    .then(() => import('../../../shared/generated/js/web'))
    .then(web => web.startWebRuntime(null, version)));

export function platformTransport(): Transport {
  return {
    call: (op, request) => started().then(page => new Promise(resolve => page.call(op, request, resolve))),
    next: (stream, request) => started().then(page => new Promise(resolve => page.next(stream, request, resolve))),
    exportPage: request =>
      started().then(page => new Promise<BinaryPage>(resolve => page.exportPage(request, resolve))),
  };
}
