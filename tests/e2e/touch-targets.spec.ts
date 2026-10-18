import { expect, test } from '@playwright/test';
import { expectTouchTargets } from '../helpers/ui-targets';

for (const width of [320, 390, 1440])
  test(`Phase 1 menus, metric controls, toggles and links keep iPhone touch targets at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    for (const picker of ['monitor-view-picker', 'monitor-range-picker']) {
      await page.getByTestId(picker).click();
      const menu = page.getByTestId('monitor-menu-dialog');
      await expectTouchTargets(menu);
      await menu.getByRole('button').first().click();
    }
    await page.getByTestId('monitor-edit').click();
    const editor = page.getByTestId('monitor-editor-dialog');
    await expectTouchTargets(editor);
    await editor.getByRole('textbox', { name: 'Search metrics', exact: true }).fill('cadence');
    await expectTouchTargets(editor);
    await editor.getByRole('button', { name: 'Clear search', exact: true }).click();
    await editor.getByRole('button', { name: 'Done', exact: true }).click();
    await page.goto('/settings');
    await expectTouchTargets(page.locator('body'));
    await page.getByTestId('settings-speed').click();
    const dialog = page.getByTestId('settings-options');
    await expect(dialog).toBeVisible();
    await expectTouchTargets(dialog);
    await page.getByRole('button', { name: 'Done', exact: true }).click();
    await page.goto('/privacy');
    await expectTouchTargets(page.locator('body'));
  });
