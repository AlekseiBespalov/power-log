import assert from 'node:assert/strict';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, resolve } from 'node:path';
import test from 'node:test';
import { chromium, expect } from '@playwright/test';
import { filesIn } from '../../scripts/web-notices.mjs';

test('public export works under the Pages path without external requests', { timeout: 60000 }, async () => {
  const root = resolve('dist');
  const prefix = '/power-log';
  const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.json': 'application/json', '.txt': 'text/plain; charset=utf-8' };
  const server = createServer((request, response) => {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    const relative = pathname.slice(prefix.length);
    if (!pathname.startsWith(`${prefix}/`) || relative.includes('..')) {
      response.writeHead(404).end(); return;
    }
    const target = resolve(root, `.${relative}`);
    const file = [target, `${target}.html`, `${target}/index.html`]
      .find(candidate => existsSync(candidate) && statSync(candidate).isFile());
    if (!file) { response.writeHead(404).end(); return; }
    response.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream');
    response.end(readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    const origin = `http://127.0.0.1:${server.address().port}`;
    browser = await chromium.launch();
    const page = await browser.newPage();
    const requests = [], errors = [], failures = [];
    page.on('request', request => requests.push(request.url()));
    page.on('pageerror', error => errors.push(error.message));
    page.on('requestfailed', request => failures.push(request.url()));
    for (const width of [390, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto(`${origin}${prefix}/`);
      await expect(page.getByTestId('ride-setup')).toBeVisible();
      await page.getByRole('link', { name: 'Settings', exact: true }).click();
      await expect(page.getByTestId('settings-speed')).toBeVisible();
      await expect(page.getByTestId('settings-screen')).not.toContainText('Strava');
      assert.equal(new URL(page.url()).pathname, `${prefix}/settings`);
      await page.reload();
      await expect(page.getByTestId('settings-speed')).toBeVisible();
      await page.getByRole('link', { name: 'History', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Open CSV', exact: true })).toBeVisible();
    }
    await page.goto(`${origin}${prefix}/?value=${'%FF'.repeat(500)}`);
    await expect(page.getByTestId('ride-setup')).toBeVisible();
    for (const name of ['.env.local', 'package.json', 'src/app/index.tsx']) {
      const response = await fetch(`${origin}${prefix}/${name}`);
      assert.equal(response.status, 404, `${name} must not be served`);
    }
    assert.ok(requests.length > 0);
    assert.deepEqual(requests.filter(url => !url.startsWith(`${origin}${prefix}/`)), []);
    assert.deepEqual(errors, []);
    assert.deepEqual(failures, []);
    assert.ok(existsSync(`${root}/.nojekyll`));
    for (const name of ['LICENSE.txt', 'THIRD_PARTY_NOTICES.txt']) {
      const response = await fetch(`${origin}${prefix}/${name}`);
      assert.equal(response.status, 200);
      assert.ok((await response.text()).length > 1000);
    }
    const notices = readFileSync(`${root}/THIRD_PARTY_NOTICES.txt`, 'utf8');
    for (const text of ['Copyright (c) Meta Platforms, Inc. and affiliates.', '--- vendor/react-helmet-async/LICENSE ---']) assert.ok(notices.includes(text), text);
    for (const file of filesIn(root)) {
      assert.ok(!file.endsWith('.map'), `Source map must not be published: ${file}`);
      if (/\.(js|css)$/.test(file)) assert.ok(!readFileSync(file, 'utf8').match(/(?:\/\/[#@]|\/\*[#@])\s*sourceMappingURL=/));
    }
  } finally {
    await browser?.close();
    await new Promise(resolve => server.close(resolve));
  }
});
