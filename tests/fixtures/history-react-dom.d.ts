declare module 'react-dom/client' {
  export function createRoot(container: Element): { render(node: import('react').ReactNode): void; unmount(): void };
}
