import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { expect, it, vi } from 'vitest';
import { NotFoundScreen } from '../../../src/features/shell/not-found-screen';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
const parts = vi.hoisted(() => ({
  container: ({ children }: { children?: unknown }) => children,
}));

vi.mock('expo-router', () => ({
  Link: ({ href, children }: { href: string; children?: ReactNode }) => createElement('a', { href }, children),
}));
vi.mock('../../../src/components/app-shell', () => ({ AppShell: parts.container }));
vi.mock('../../../src/components/ui', () => ({
  Heading: parts.container,
  colors: {},
  type: {},
  MIN_TOUCH_TARGET: 48,
}));

it('says the page does not exist and links back to Ride', () => {
  const markup = renderToStaticMarkup(createElement(NotFoundScreen));
  expect(markup).toContain('This page does not exist.');
  expect(markup).toContain('<a href="/">Return to Ride</a>');
});
