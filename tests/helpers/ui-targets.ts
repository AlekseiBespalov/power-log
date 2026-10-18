import { expect, type Locator } from '@playwright/test';

export async function expectTouchTargets(surface: Locator) {
  const controls = surface
    .locator(
      'button, a[href], [role="button"], [role="switch"], [role="checkbox"], [role="radio"], [role="tab"], [role="slider"]',
    )
    .filter({ visible: true });
  const undersized = await controls.evaluateAll(elements =>
    elements.flatMap(element => {
      const bounds = element.getBoundingClientRect();
      return bounds.width < 47.99 || bounds.height < 47.99
        ? [
            {
              label: element.getAttribute('aria-label') ?? element.textContent,
              width: bounds.width,
              height: bounds.height,
            },
          ]
        : [];
    }),
  );
  expect(undersized).toEqual([]);
}
