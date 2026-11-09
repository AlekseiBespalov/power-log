import { readFileSync } from 'node:fs';
import { extractExpoPathFromURL } from 'expo-router/build/fork/extractPathFromURL';
import { describe, expect, it } from 'vitest';

const ride = '11111111-1111-4111-8111-111111111111';
const token = '66666666-6666-4666-8666-666666666666';
// The data URI AndroidRideCardTest.theFinishIntentCarriesThePathlessUrlThatRoutesToTheRideTab pins.
const finishUrl = `power-log:///?control=${ride}%2F${token}%2Frunning%2Ffinish`;

describe('the Android ride notification Finish URL', () => {
  it('routes to the index route, the Ride tab, which opens the Finish sheet from the finish request', () => {
    const path = extractExpoPathFromURL([], finishUrl);
    expect(path.split('?')[0]).toBe('');
    expect(path).toBe(`?control=${ride}/${token}/running/finish`);
    expect(readFileSync('src/app/index.tsx', 'utf8')).toContain(
      "export { RecordingScreen as default } from '../features/recording/recording-screen';",
    );
  });

  it('a host in the URL would become a route the app does not have', () => {
    expect(extractExpoPathFromURL([], `power-log://ride-card?control=${ride}`).split('?')[0]).toBe('ride-card');
  });
});
