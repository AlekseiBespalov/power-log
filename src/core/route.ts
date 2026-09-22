import type { RoutePoint } from './workouts';

/** Local equirectangular preview. The archive retains the original GPS samples. */
export function routePaths(points: RoutePoint[], width: number, height: number): string[] {
  const valid = points.filter(point => Number.isFinite(point.latitude) && Number.isFinite(point.longitude) && Math.abs(point.latitude) <= 90 && Math.abs(point.longitude) <= 180);
  if (valid.length < 2) return [];
  const first = valid[0]!;
  const longitudeScale = Math.cos(first.latitude * Math.PI / 180);
  let previousLongitude = first.longitude;
  const projected = valid.map(point => {
    let longitude = point.longitude;
    while (longitude - previousLongitude > 180) longitude -= 360;
    while (longitude - previousLongitude < -180) longitude += 360;
    previousLongitude = longitude;
    return { x: longitude * longitudeScale, y: -point.latitude, segment: point.segment ?? 0 };
  });
  const xs = projected.map(point => point.x), ys = projected.map(point => point.y);
  const left = Math.min(...xs), top = Math.min(...ys);
  const spanX = Math.max(...xs) - left, spanY = Math.max(...ys) - top;
  const scale = Math.min((width - 32) / Math.max(spanX, 1e-7), (height - 32) / Math.max(spanY, 1e-7));
  const offsetX = (width - spanX * scale) / 2, offsetY = (height - spanY * scale) / 2;
  const paths: string[] = [];
  let previousSegment: number | undefined;
  for (const point of projected) {
    const coordinates = `${((point.x - left) * scale + offsetX).toFixed(2)},${((point.y - top) * scale + offsetY).toFixed(2)}`;
    if (!paths.length || point.segment !== previousSegment) paths.push(`M${coordinates}`);
    else paths[paths.length - 1] += ` L${coordinates}`;
    previousSegment = point.segment;
  }
  return paths;
}
