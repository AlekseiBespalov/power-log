import { describe, expect, it } from 'vitest';
import { workoutPermissionAction } from '../../src/core/workout-permissions';
import type { WorkoutPermissionStatus } from '../../src/core/workouts';

const phone = { indoor: false, useWatch: false };
const watch = { indoor: false, useWatch: true };
function status(): WorkoutPermissionStatus {
  return {
    health: {
      available: true,
      writeAuthorization: Object.fromEntries(
        [
          'HKWorkoutTypeIdentifier',
          'HKQuantityTypeIdentifierCyclingPower',
          'HKQuantityTypeIdentifierCyclingCadence',
          'HKQuantityTypeIdentifierActiveEnergyBurned',
          'HKQuantityTypeIdentifierBasalEnergyBurned',
          'HKQuantityTypeIdentifierHeartRate',
          'HKWorkoutRouteTypeIdentifier',
          'HKQuantityTypeIdentifierCyclingSpeed',
          'HKQuantityTypeIdentifierDistanceCycling',
        ].map(key => [key, 'authorized']),
      ),
    },
    location: 'authorizedWhenInUse',
    locationServicesEnabled: true,
    locationAccuracyAuthorization: 'full',
  };
}
describe('workout permission actions', () => {
  it('hides setup after known phone write/location setup without claiming read authorization', () => {
    expect(workoutPermissionAction(status(), phone).action).toBe('none');
  });
  it('shows setup when a selected Health write type has not been requested', () => {
    const value = status();
    value.health.writeAuthorization.HKWorkoutTypeIdentifier = 'notDetermined';
    expect(workoutPermissionAction(value, phone).action).toBe('request');
  });
  it('ignores phone GPS for a Watch-owned route', () => {
    const value = status();
    value.location = 'denied';
    value.locationServicesEnabled = false;
    expect(workoutPermissionAction(value, watch).action).toBe('none');
  });
  it('ignores GPS and route-write permissions for an indoor phone ride', () => {
    const value = status();
    value.location = 'denied';
    value.health.writeAuthorization.HKWorkoutRouteTypeIdentifier = 'denied';
    expect(workoutPermissionAction(value, { ...phone, indoor: true }).action).toBe('none');
  });
  it('offers a location prompt only for undetermined outdoor phone access', () => {
    const value = status();
    value.location = 'notDetermined';
    expect(workoutPermissionAction(value, phone).action).toBe('request');
  });
  it.each(['denied', 'restricted'])('routes %s location to Settings rather than another prompt', location => {
    const value = status();
    value.location = location;
    expect(workoutPermissionAction(value, phone)).toMatchObject({ action: 'settings', settingsTarget: 'app' });
  });
  it('detects disabled global location services even when app access was granted', () => {
    const value = status();
    value.locationServicesEnabled = false;
    expect(workoutPermissionAction(value, phone)).toMatchObject({ action: 'settings', settingsTarget: 'app' });
  });
  it('does not infer grants from HealthKit unnecessary after a known write denial', () => {
    const value = status();
    value.health.writeAuthorization.HKWorkoutTypeIdentifier = 'denied';
    expect(workoutPermissionAction(value, phone)).toMatchObject({ action: 'settings', settingsTarget: 'health' });
  });
  it('does not claim that phone write grants describe the Watch owner', () => {
    const value = status();
    value.health.writeAuthorization.HKWorkoutTypeIdentifier = 'denied';
    expect(workoutPermissionAction(value, watch).action).toBe('none');
  });
  it('keeps unknown status distinct from granted and does not request automatically', () => {
    const value = status();
    value.health.writeAuthorization.HKWorkoutTypeIdentifier = 'unknown';
    expect(workoutPermissionAction(value, phone).action).toBe('check');
    expect(workoutPermissionAction(null, phone).action).toBe('check');
  });
  it('reflects Settings changes from fresh snapshots, without a sticky granted cache', () => {
    const first = status();
    expect(workoutPermissionAction(first, phone).action).toBe('none');
    const revoked = status();
    revoked.location = 'denied';
    expect(workoutPermissionAction(revoked, phone).action).toBe('settings');
    expect(workoutPermissionAction(status(), phone).action).toBe('none');
  });
  it('shows a precise-location Settings action only for the phone route owner', () => {
    const value = status();
    value.locationAccuracyAuthorization = 'reduced';
    expect(workoutPermissionAction(value, phone)).toMatchObject({ action: 'settings', settingsTarget: 'app' });
    expect(workoutPermissionAction(value, watch).action).toBe('none');
    expect(workoutPermissionAction(value, { ...phone, indoor: true }).action).toBe('none');
  });
  it('can record locally without requesting Health or location, even when both are unavailable', () => {
    const value = status();
    value.health.available = false;
    value.health.writeAuthorization = {};
    value.location = 'denied';
    value.locationServicesEnabled = false;
    const local = { ...phone, saveToHealth: false, recordGPS: false };
    expect(workoutPermissionAction(null, local).action).toBe('none');
    expect(workoutPermissionAction(value, local).action).toBe('none');
  });
  it('requests only location when a local ride enables GPS without Health', () => {
    const value = status();
    value.health.available = false;
    value.health.writeAuthorization = {};
    value.location = 'notDetermined';
    expect(workoutPermissionAction(value, { ...phone, saveToHealth: false, recordGPS: true })).toMatchObject({
      action: 'request',
      label: 'Allow location',
    });
    value.location = 'authorizedWhenInUse';
    expect(workoutPermissionAction(value, { ...phone, saveToHealth: false, recordGPS: true }).action).toBe('none');
  });
  it('does not require location or route writes when an outdoor ride disables GPS', () => {
    const value = status();
    value.location = 'denied';
    value.health.writeAuthorization.HKWorkoutRouteTypeIdentifier = 'denied';
    expect(workoutPermissionAction(value, { ...phone, recordGPS: false }).action).toBe('none');
  });
  it('does not repeat setup for unselected route types', () => {
    const value = status();
    value.health.writeAuthorization.HKWorkoutRouteTypeIdentifier = 'notDetermined';
    value.health.writeAuthorization.HKQuantityTypeIdentifierCyclingSpeed = 'notDetermined';
    value.health.writeAuthorization.HKQuantityTypeIdentifierDistanceCycling = 'notDetermined';
    expect(workoutPermissionAction(value, { ...phone, recordGPS: false }).action).toBe('none');
    expect(workoutPermissionAction(value, { ...phone, recordGPS: true }).action).toBe('request');
  });
  it.each([true, false])('leaves Watch authorization to its owner when Health saving is %s', saveToHealth => {
    const value = status();
    value.health.available = false;
    value.health.writeAuthorization = {};
    value.location = 'denied';
    expect(workoutPermissionAction(value, { ...watch, saveToHealth }).action).toBe('none');
    expect(workoutPermissionAction(null, { ...watch, saveToHealth }).action).toBe('none');
  });
  it('treats HealthKit unavailability separately from a permission prompt', () => {
    const value = status();
    value.health.available = false;
    expect(workoutPermissionAction(value, phone).action).toBe('unavailable');
  });
});

const exerciseWrite = 'android.permission.health.WRITE_EXERCISE';
const powerWrite = 'android.permission.health.WRITE_POWER';
const distanceWrite = 'android.permission.health.WRITE_DISTANCE';
const speedWrite = 'android.permission.health.WRITE_SPEED';
const routeWrite = 'android.permission.health.WRITE_EXERCISE_ROUTE';
function androidStatus(): WorkoutPermissionStatus {
  return {
    ...status(),
    health: {
      available: true,
      provider: 'healthConnect',
      requiredWrites: [exerciseWrite],
      writeAuthorization: Object.fromEntries(
        [exerciseWrite, powerWrite, distanceWrite, speedWrite, routeWrite].map(type => [type, 'authorized']),
      ),
    },
  };
}

describe('Android Health Connect permission parity', () => {
  it('has no setup action with all requested grants', () => {
    expect(workoutPermissionAction(androidStatus(), phone)).toEqual(workoutPermissionAction(status(), phone));
  });

  it.each([
    [powerWrite, 'HKQuantityTypeIdentifierCyclingPower'],
    [distanceWrite, 'HKQuantityTypeIdentifierDistanceCycling'],
    [speedWrite, 'HKQuantityTypeIdentifierCyclingSpeed'],
    [routeWrite, 'HKWorkoutRouteTypeIdentifier'],
  ])('offers setup for missing %s like an undetermined iPhone write type', (androidType, iphoneType) => {
    const android = androidStatus();
    android.health.writeAuthorization[androidType] = 'notDetermined';
    const iphone = status();
    iphone.health.writeAuthorization[iphoneType] = 'notDetermined';
    expect(workoutPermissionAction(android, phone)).toEqual(workoutPermissionAction(iphone, phone));
    expect(workoutPermissionAction(android, phone)).toMatchObject({ action: 'request', label: 'Set up permissions' });
    iphone.health.writeAuthorization[iphoneType] = 'denied';
    expect(workoutPermissionAction(iphone, phone)).toMatchObject({ action: 'settings', settingsTarget: 'health' });
  });

  it.each(['notDetermined', 'denied', 'unknown'])('offers setup when session access is %s', authorization => {
    const android = androidStatus();
    android.health.writeAuthorization[exerciseWrite] = authorization;
    const iphone = status();
    iphone.health.writeAuthorization.HKWorkoutTypeIdentifier = 'notDetermined';
    expect(workoutPermissionAction(android, phone)).toEqual(workoutPermissionAction(iphone, phone));
    expect(workoutPermissionAction(android, phone)).toMatchObject({ action: 'request', label: 'Set up permissions' });
  });

  it('offers setup for the initial Android payload with no grants', () => {
    const android = androidStatus();
    android.health.writeAuthorization = Object.fromEntries(
      Object.keys(android.health.writeAuthorization).map(type => [type, 'notDetermined']),
    );
    expect(workoutPermissionAction(android, phone).action).toBe('request');
  });

  it('offers setup for speed and route when GPS is enabled after Health setup without GPS', () => {
    const android = androidStatus();
    android.health.writeAuthorization[routeWrite] = 'notDetermined';
    android.health.writeAuthorization[speedWrite] = 'notDetermined';
    const iphone = status();
    iphone.health.writeAuthorization.HKWorkoutRouteTypeIdentifier = 'notDetermined';
    iphone.health.writeAuthorization.HKQuantityTypeIdentifierCyclingSpeed = 'notDetermined';
    const options = { ...phone, recordGPS: false };
    expect(workoutPermissionAction(android, options)).toEqual(workoutPermissionAction(iphone, options));
    expect(workoutPermissionAction(android, { ...options, recordGPS: true })).toEqual(
      workoutPermissionAction(iphone, { ...options, recordGPS: true }),
    );
    expect(workoutPermissionAction(android, { ...options, recordGPS: true })).toMatchObject({
      action: 'request',
      label: 'Set up permissions',
    });
    expect(workoutPermissionAction(android, { ...phone, indoor: true }).action).toBe('none');
    expect(workoutPermissionAction(android, { ...phone, indoor: true, recordGPS: true }).action).toBe('request');
    android.health.writeAuthorization[powerWrite] = 'notDetermined';
    expect(workoutPermissionAction(android, options)).toMatchObject({ action: 'request', label: 'Set up permissions' });
  });

  it.each([powerWrite, distanceWrite])('offers setup for missing %s even with GPS off', type => {
    const android = androidStatus();
    android.health.writeAuthorization[type] = 'notDetermined';
    const options = { ...phone, indoor: true, recordGPS: false };
    expect(workoutPermissionAction(android, options)).toMatchObject({ action: 'request', label: 'Set up permissions' });
    const iphone = status();
    iphone.health.writeAuthorization.HKQuantityTypeIdentifierDistanceCycling = 'notDetermined';
    expect(workoutPermissionAction(iphone, options).action).toBe('none');
  });

  it('does not turn optional write grants into a location grant', () => {
    const android = androidStatus();
    const iphone = status();
    android.location = iphone.location = 'notDetermined';
    expect(workoutPermissionAction(android, phone)).toEqual(workoutPermissionAction(iphone, phone));
    android.location = iphone.location = 'denied';
    expect(workoutPermissionAction(android, phone)).toEqual(workoutPermissionAction(iphone, phone));
  });

  it('ignores Health grants for local-only recording and reports unavailable Health separately', () => {
    const android = androidStatus();
    android.health.available = false;
    android.health.writeAuthorization = {};
    const iphone = status();
    iphone.health.available = false;
    iphone.health.writeAuthorization = {};
    const options = { ...phone, saveToHealth: false, recordGPS: false };
    expect(workoutPermissionAction(android, options)).toEqual(workoutPermissionAction(iphone, options));
    expect(workoutPermissionAction(android, phone)).toEqual(workoutPermissionAction(iphone, phone));
  });
});
