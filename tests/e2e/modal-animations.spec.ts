import { expect, test, type Page } from '@playwright/test';

type Frame = { x: number; y: number; width: number; height: number; opacity: number; translated: boolean };
type ProbeWindow = Window & { modalFrames: Frame[]; stopModalProbe: () => void };

async function observeBackdrop(page: Page, id: string) {
  await page.evaluate(id => {
    const probe = window as unknown as ProbeWindow;
    probe.stopModalProbe?.();
    probe.modalFrames = [];
    let frame = 0;
    const tick = () => {
      const element = document.querySelector(`[data-testid="${id}-backdrop"]`);
      if (element) {
        let opacity = 1, translated = false;
        for (let node: Element | null = element; node; node = node.parentElement) {
          const style = getComputedStyle(node);
          opacity *= Number(style.opacity);
          const transform = new DOMMatrixReadOnly(style.transform);
          translated ||= Math.abs(transform.m41) > 0.1 || Math.abs(transform.m42) > 0.1;
        }
        const { x, y, width, height } = element.getBoundingClientRect();
        probe.modalFrames.push({ x, y, width, height, opacity, translated });
      }
      frame = requestAnimationFrame(tick);
    };
    probe.stopModalProbe = () => cancelAnimationFrame(frame);
    frame = requestAnimationFrame(tick);
  }, id);
}
async function expectStationaryFade(page: Page, width: number, opening: boolean) {
  const frames = await page.evaluate(() => (window as unknown as ProbeWindow).modalFrames);
  expect(frames.length).toBeGreaterThan(2);
  expect(frames.some(frame => frame.opacity > 0.05 && frame.opacity < 0.95)).toBe(true);
  for (const frame of frames) {
    expect(frame.translated).toBe(false);
    expect(frame.x).toBeCloseTo(0, 0); expect(frame.y).toBeCloseTo(0, 0);
    expect(frame.width).toBeCloseTo(width, 0); expect(frame.height).toBeCloseTo(900, 0);
  }
  const first = frames[0]!.opacity, last = frames.at(-1)!.opacity;
  if (opening) expect(last).toBeGreaterThan(first);
  else expect(last).toBeLessThan(first);
}

for (const width of [390, 1440]) test(`selectors, editor and ride setup share a stationary fade at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await page.goto('/');
  for (const [opener, dialog, closer] of [
    ['monitor-range-picker', 'monitor-menu-dialog', 'Close menu'],
    ['monitor-view-picker', 'monitor-menu-dialog', 'Close menu'],
    ['monitor-edit', 'monitor-editor-dialog', 'Close monitor editor'],
    ['ride-setup', 'ride-setup-sheet', 'Close ride setup'],
  ]) {
    await observeBackdrop(page, dialog!);
    await page.getByTestId(opener!).click();
    await expect(page.getByTestId(dialog!)).toBeVisible();
    await expect.poll(() => page.evaluate(() => (window as unknown as ProbeWindow).modalFrames.at(-1)?.opacity)).toBe(1);
    await expectStationaryFade(page, width, true);
    if (opener === 'monitor-edit') {
      await page.getByRole('tab', { name: 'Graphs', exact: true }).click();
      await page.getByRole('tab', { name: 'Numbers', exact: true }).click();
      await expect(page.getByRole('textbox', { name: 'Search metrics' })).toBeVisible();
    }
    await observeBackdrop(page, dialog!);
    await page.getByRole('button', { name: closer!, exact: true }).click({ position: { x: 8, y: 8 } });
    await expect(page.getByTestId(dialog!)).toHaveCount(0);
    await expectStationaryFade(page, width, false);
  }
});
