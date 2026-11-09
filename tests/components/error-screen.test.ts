import { createElement, type ReactNode } from 'react';
import { createRequire } from 'node:module';
import { afterEach, expect, it, vi } from 'vitest';
import { ErrorScreen } from '../../src/components/error-screen';

const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server') as {
  renderToStaticMarkup: (node: ReactNode) => string;
};
function container({ children }: { children?: ReactNode }) {
  return createElement('div', null, children);
}
vi.mock('react-native', () => ({ View: container }));
vi.mock('react-native-safe-area-context', () => ({ SafeAreaProvider: container, SafeAreaView: container }));
vi.mock('../../src/components/ui', () => ({
  Body: container,
  Heading: container,
  Button: ({ children }: { children?: ReactNode }) => createElement('button', null, children),
  colors: {},
  space: {},
}));
afterEach(() => vi.unstubAllGlobals());

const detail = "TypeError: Cannot read properties of undefined (reading 'speedUnit')";
const render = () =>
  renderToStaticMarkup(createElement(ErrorScreen, { error: new Error(detail), retry: async () => {} }));

it('shows the error detail only in development builds', () => {
  vi.stubGlobal('__DEV__', true);
  expect(render()).toContain('Cannot read properties of undefined');
  vi.stubGlobal('__DEV__', false);
  const release = render();
  expect(release).not.toContain('Cannot read properties of undefined');
  expect(release).not.toContain('TypeError');
  expect(release).toContain('Something went wrong on this screen.');
  expect(release).toContain('Try again, or close and reopen Power Log if it happens again.');
  expect(release).toContain('<button>Try again</button>');
});
