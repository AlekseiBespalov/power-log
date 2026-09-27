import { expect, test } from '@playwright/test';
import { version } from '../../package.json';

const destinations = [
  ['GitHub Issues', 'https://github.com/AlekseiBespalov/power-log/issues'],
  ['Source repository', 'https://github.com/AlekseiBespalov/power-log'],
  ['Third-party notices', 'https://AlekseiBespalov.github.io/power-log/THIRD_PARTY_NOTICES.txt'],
] as const;

for (const width of [320, 390, 1440]) {
  test(`About links and privacy policy are readable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/settings');
    const about = page.getByTestId('settings-about');
    await expect(about.getByRole('heading', { name: 'About', exact: true })).toBeVisible();
    await expect(about).toContainText(`Power Log ${version}`);
    await expect(about).toContainText('Notices for the published website build.');
    for (const [name, href] of destinations) {
      const link = about.getByRole('link', { name, exact: true });
      await expect(link).toHaveAttribute('href', href);
      await expect(link).toHaveAttribute('target', '_blank');
      await expect(link).toHaveAttribute('rel', /noopener/);
    }
    const privacyLink = about.getByRole('link', { name: 'Privacy policy', exact: true });
    await expect(privacyLink).toHaveAttribute('href', '/privacy');
    await privacyLink.click();
    await expect(page).toHaveURL(/\/privacy$/);
    await page.reload();
    const policy = page.getByTestId('privacy-screen');
    await expect(policy.getByRole('heading', { name: 'Privacy policy', exact: true })).toBeVisible();
    await expect(policy).toContainText(`Describes Power Log ${version}`);
    for (const title of [
      'What stays on your devices',
      'Bluetooth and location',
      'Apple Health',
      'Health Connect on Android',
      'Recording indicators',
      'What leaves the app',
      'Keeping and deleting data',
      'Children',
      'Changes and contact',
    ]) {
      const heading = policy.getByRole('heading', { name: title, exact: true });
      await heading.scrollIntoViewIfNeeded();
      await expect(heading).toBeVisible();
    }
    await expect(policy).toContainText('heart rate variability');
    await expect(policy).toContainText('does not request permission to read your Health Connect records');
    const contact = policy.getByRole('link', { name: 'GitHub Issues', exact: true });
    await expect(contact).toHaveAttribute('href', destinations[0][1]);
    await expect(contact).toHaveAttribute('target', '_blank');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(
      await policy.evaluate(element =>
        [...element.querySelectorAll<HTMLElement>('[dir="auto"],a')]
          .filter(node => node.scrollWidth > node.clientWidth + 1)
          .map(node => node.textContent),
      ),
    ).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`privacy-${width}.png`), fullPage: true });
    await policy.getByRole('link', { name: 'Back to Settings', exact: true }).click();
    await expect(page).toHaveURL(/\/settings$/);
    await expect(about).toBeVisible();
  });
}
