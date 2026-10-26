export const counts = {
  renders: new Map<string, number>(),
  scenes: new Map<string, number>(),
  nativeScenes: new Map<string, number>(),
};
export function count(map: Map<string, number>, key: string) {
  map.set(key, (map.get(key) ?? 0) + 1);
}
export function resetCounts() {
  for (const map of Object.values(counts)) map.clear();
}
export function countsSnapshot() {
  return Object.fromEntries(Object.entries(counts).map(([name, map]) => [name, Object.fromEntries(map)])) as Record<
    keyof typeof counts,
    Record<string, number>
  >;
}
