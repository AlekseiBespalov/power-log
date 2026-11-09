import assert from 'node:assert/strict';
import test from 'node:test';

test('the web and kit builds import in Node without a browser and starts nothing until startWebRuntime', async () => {
  const touched = [];
  for (const name of ['window', 'document', 'navigator', 'indexedDB', 'localStorage'])
    Object.defineProperty(globalThis, name, {
      configurable: true,
      get() {
        touched.push(name);
        return undefined;
      },
    });
  try {
    const web = await import('../../shared/generated/js/web/index.mjs');
    assert.equal(typeof web.startWebRuntime, 'function');
    assert.equal(typeof web.WebSession, 'function');
    const kit = await import('../../shared/generated/js/kit/power-log-kit.mjs');
    assert.ok(Object.keys(kit).length > 0);
    assert.deepEqual(touched, []);
  } finally {
    for (const name of ['window', 'document', 'navigator', 'indexedDB', 'localStorage']) delete globalThis[name];
  }
});
