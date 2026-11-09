import { useCallback, useLayoutEffect, useState, type RefCallback } from 'react';
import type { View } from 'react-native';

const modals: HTMLElement[] = [];
const background = new Map<HTMLElement, { inert: boolean; hidden: string | null }>();
let observer: MutationObserver | null = null;

function restore(element: HTMLElement) {
  const previous = background.get(element);
  if (!previous) return;
  element.inert = previous.inert;
  if (previous.hidden === null) element.removeAttribute('aria-hidden');
  else element.setAttribute('aria-hidden', previous.hidden);
}

function isolate() {
  const active = modals.at(-1);
  if (!active) {
    for (const element of background.keys()) restore(element);
    background.clear();
    return;
  }
  for (const element of document.body.children) {
    if (!(element instanceof HTMLElement)) continue;
    if (element.contains(active)) {
      restore(element);
      continue;
    }
    if (!background.has(element))
      background.set(element, { inert: element.inert, hidden: element.getAttribute('aria-hidden') });
    element.inert = true;
    element.setAttribute('aria-hidden', 'true');
  }
}

export function useModalAccessibility(visible: boolean): RefCallback<View> {
  const [host, setHost] = useState<HTMLElement | null>(null);
  const ref = useCallback((node: View | null) => {
    setHost(node instanceof HTMLElement ? node : null);
  }, []);
  useLayoutEffect(() => {
    if (!host) return;
    host.toggleAttribute('inert', !visible);
    if (!visible) return;
    modals.push(host);
    if (!observer) {
      observer = new MutationObserver(isolate);
      observer.observe(document.body, { childList: true });
    }
    isolate();
    return () => {
      host.toggleAttribute('inert', true);
      modals.splice(modals.indexOf(host), 1);
      isolate();
      if (!modals.length) {
        observer?.disconnect();
        observer = null;
      }
    };
  }, [host, visible]);
  return ref;
}
