import { createElement, type ReactNode } from 'react';

const shape = (tag: string) =>
  function Shape({ children }: { children?: ReactNode }) {
    return createElement(tag, null, children);
  };
export default shape('svg');
export const G = shape('g');
export const Path = shape('path');
export const Line = shape('line');
export const Circle = shape('circle');
export const Rect = shape('rect');
export const ClipPath = shape('clipPath');
export const Defs = shape('defs');
export const Text = shape('text');
