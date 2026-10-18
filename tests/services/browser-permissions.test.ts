import { afterEach, expect, it, vi } from 'vitest';
import { BrowserWorkoutRecorder } from '../../src/services/browser-workout-recorder';
import { browserGPSPermission, requestBrowserGPSPermission } from '../../src/services/browser-gps';
import { workoutPermissionAction } from '../../src/services/workout-permission-action.web';

it('accepts a browser location grant without inventing precise-location authorization', async () => {
  const permissions = await new BrowserWorkoutRecorder().getPermissions();
  expect(
    workoutPermissionAction(
      { ...permissions, location: 'authorizedWhenInUse', locationServicesEnabled: true },
      { indoor: false, recordGPS: true, saveToHealth: false },
    ),
  ).toEqual({ action: 'none', label: '', detail: '' });
  expect(permissions.locationAccuracyAuthorization).toBe('unknown');
});
it('retains the permission request and adapts denied location to a browser settings check', async () => {
  const permissions = { ...(await new BrowserWorkoutRecorder().getPermissions()), locationServicesEnabled: true };
  const options = { indoor: false, recordGPS: true, saveToHealth: false };
  expect(workoutPermissionAction({ ...permissions, location: 'notDetermined' }, options)).toMatchObject({
    action: 'request',
    label: 'Allow location',
  });
  expect(workoutPermissionAction({ ...permissions, location: 'denied' }, options)).toMatchObject({
    action: 'settings',
    label: 'Check permissions',
    detail: "Allow location in this site's browser settings, then check permissions.",
  });
  expect(
    workoutPermissionAction({ ...permissions, location: 'denied' }, { ...options, recordGPS: false }),
  ).toMatchObject({ action: 'none' });
});

afterEach(() => vi.unstubAllGlobals());
it('uses successful Geolocation authorization evidence when the browser has no Permissions API', async () => {
  vi.stubGlobal('navigator', {
    geolocation: { getCurrentPosition: (success: PositionCallback) => success({} as GeolocationPosition) },
  });
  expect(await browserGPSPermission()).toBe('notDetermined');
  await requestBrowserGPSPermission();
  expect(await browserGPSPermission()).toBe('authorizedWhenInUse');
  expect(
    workoutPermissionAction(await new BrowserWorkoutRecorder().getPermissions(), {
      indoor: false,
      recordGPS: true,
      saveToHealth: false,
    }),
  ).toMatchObject({ action: 'none' });
});
