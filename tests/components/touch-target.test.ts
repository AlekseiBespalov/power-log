import { createElement } from 'react';
import { createRequire } from 'node:module';
import type { PressableProps } from 'react-native';
import { expect, it, vi } from 'vitest';
import { MIN_TOUCH_TARGET, Pressable, type } from '../../src/components/ui';

const pressState = { pressed: false, hovered: false };

const captured = vi.hoisted(() => ({ style: {} as { minWidth: number; minHeight: number } }));
vi.mock('react-native', async () => {
  const { createRequire } = await import('node:module');
  const native = createRequire(import.meta.url)('react-native-web');
  return {
    ...native,
    Pressable: ({ style }: PressableProps) => {
      captured.style = native.StyleSheet.flatten(typeof style === 'function' ? style(pressState) : style);
      return null;
    },
  };
});
const { renderToStaticMarkup } = createRequire(import.meta.url)('react-dom/server');

it.each([
  undefined,
  { width: 30, height: 30, minWidth: 0, minHeight: 32 },
  [{ minWidth: 44, minHeight: 44 }, false],
  () => ({ minWidth: 38, minHeight: 32 }),
] satisfies PressableProps['style'][])('finds no undersized target for style %j', style => {
  renderToStaticMarkup(createElement(Pressable, { style }));
  expect(MIN_TOUCH_TARGET).toBe(48);
  expect(captured.style.minWidth).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
  expect(captured.style.minHeight).toBeGreaterThanOrEqual(MIN_TOUCH_TARGET);
});

it('preserves larger targets and the shared type scale', () => {
  renderToStaticMarkup(createElement(Pressable, { style: { minWidth: 72, minHeight: 76 } }));
  expect(captured.style).toMatchObject({ minWidth: 72, minHeight: 76 });
  expect(type).toEqual({ axis: 10, label: 11, caption: 12, body: 14, control: 15, title: 18, value: 26, hero: 38 });
});
