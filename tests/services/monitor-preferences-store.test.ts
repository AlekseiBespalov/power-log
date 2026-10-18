import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defaultMonitorPreferences, monitorPreferencesJson } from '../../src/core/monitor';
import { MonitorPreferencesController } from '../../src/services/monitor-preferences-controller';

const fs = vi.hoisted(() => ({
  files: new Map<string, string>(),
  directories: new Set<string>(),
  failList: false,
  failMove: false,
  failWrite: false,
  moves: [] as unknown[],
}));
const expoFailure = (name: string) =>
  new Error(
    `Calling the '${name}' function has failed\n→ Caused by: NSCocoaErrorDomain 640 (at ExpoModulesCore/FileSystem.swift:12)`,
  );
vi.mock('expo-file-system', () => ({
  Paths: { document: 'file:///documents' },
  Directory: class {
    uri: string;
    constructor(parent: string, name: string) {
      this.uri = `${parent}/${name}`;
    }
    get exists() {
      return fs.directories.has(this.uri);
    }
    create() {
      fs.directories.add(this.uri);
    }
    list() {
      if (fs.failList) throw expoFailure('list');
      return [...fs.files.keys()]
        .filter(uri => uri.startsWith(`${this.uri}/`))
        .map(uri => ({ name: uri.slice(this.uri.length + 1) }));
    }
  },
  File: class {
    uri: string;
    constructor(parent: { uri: string }, name: string) {
      this.uri = `${parent.uri}/${name}`;
    }
    get size() {
      return fs.files.get(this.uri)?.length ?? 0;
    }
    textSync() {
      const value = fs.files.get(this.uri);
      if (value === undefined) throw new Error('Missing file');
      return value;
    }
    write(contents: string) {
      if (fs.failWrite) throw expoFailure('write');
      fs.files.set(this.uri, contents);
    }
    moveSync(destination: { uri: string }, options?: unknown) {
      fs.moves.push(options);
      if (fs.failMove) throw expoFailure('move');
      if (fs.files.has(destination.uri)) throw new Error('Destination exists');
      fs.files.set(destination.uri, this.textSync());
      fs.files.delete(this.uri);
      this.uri = destination.uri;
    }
    delete() {
      fs.files.delete(this.uri);
    }
  },
}));
beforeEach(() => {
  vi.resetModules();
  fs.files.clear();
  fs.directories.clear();
  fs.moves = [];
  fs.failList = false;
  fs.failMove = false;
  fs.failWrite = false;
});
afterEach(() => {
  vi.unstubAllGlobals();
});
const native = async () =>
  (await import('../../src/services/monitor-preferences-store.native')).monitorPreferencesStore;
const file = (revision: number, suffix = 'json') =>
  `file:///documents/power-log-preferences/monitor-${String(revision).padStart(16, '0')}.${suffix}`;
const defaults = JSON.parse(monitorPreferencesJson(defaultMonitorPreferences()));
const invalidEnvelopes = [
  { name: 'null', value: null },
  { name: 'empty object', value: {} },
  { name: 'array', value: [] },
  { name: 'wrong version', value: { ...defaults, version: 2 } },
  { name: 'unknown envelope field', value: { ...defaults, other: true } },
  { name: 'missing active view', value: { ...defaults, activeView: undefined } },
  { name: 'wrong active view type', value: { ...defaults, activeView: 1 } },
  { name: 'unknown active view', value: { ...defaults, activeView: 'other' } },
  { name: 'wrong history view type', value: { ...defaults, historyView: null } },
  { name: 'invalid history range', value: { ...defaults, historyRange: '120' } },
  { name: 'invalid speed unit', value: { ...defaults, speedUnit: 'knots' } },
  { name: 'non-text distance source', value: { ...defaults, distanceSource: 7 } },
  { name: 'invalid sample rate', value: { ...defaults, sampleHz: '8' } },
  { name: 'missing options', value: { ...defaults, workoutOptions: undefined } },
  { name: 'null options', value: { ...defaults, workoutOptions: null } },
  { name: 'unknown option', value: { ...defaults, workoutOptions: { ...defaults.workoutOptions, other: true } } },
  {
    name: 'invalid Watch choice',
    value: { ...defaults, workoutOptions: { ...defaults.workoutOptions, useWatch: 'false' } },
  },
  {
    name: 'missing Health choice',
    value: { ...defaults, workoutOptions: { ...defaults.workoutOptions, saveToHealth: undefined } },
  },
  { name: 'invalid GPS choice', value: { ...defaults, workoutOptions: { ...defaults.workoutOptions, recordGPS: 1 } } },
  {
    name: 'invalid options sample rate',
    value: { ...defaults, workoutOptions: { ...defaults.workoutOptions, sampleHz: 3 } },
  },
  { name: 'missing views', value: { ...defaults, views: undefined } },
  { name: 'array views', value: { ...defaults, views: [] } },
  { name: 'unknown preset', value: { ...defaults, views: { ...defaults.views, other: defaults.views.ride } } },
  {
    name: 'unknown preset field',
    value: { ...defaults, views: { ...defaults.views, ride: { ...defaults.views.ride, other: true } } },
  },
  { name: 'missing preset', value: { ...defaults, views: { ...defaults.views, battery: undefined } } },
  {
    name: 'duplicate metric',
    value: {
      ...defaults,
      views: { ...defaults.views, ride: { ...defaults.views.ride, charts: ['humanPowerW', 'humanPowerW'] } },
    },
  },
  ...Object.entries({
    id: 'battery',
    name: 7,
    range: '120',
    webColumns: 4,
    numbers: 'humanPowerW',
    charts: [null],
  }).map(([key, value]) => ({
    name: `invalid preset ${key}`,
    value: { ...defaults, views: { ...defaults.views, ride: { ...defaults.views.ride, [key]: value } } },
  })),
];

describe('native monitor preference commits', () => {
  it('loads defaults without creating a preferences file', async () => {
    const preferences = (await (await native()).load()).preferences;
    expect(preferences).toEqual(defaultMonitorPreferences());
    expect(preferences.workoutOptions.useWatch).toBe(false);
    expect(preferences.views.ride.numbers).toEqual([
      'humanPowerW',
      'cadenceRpm',
      'motorInputPowerW',
      'controllerSpeedMps',
    ]);
    expect(fs.files.size).toBe(0);
    expect(fs.directories.size).toBe(0);
  });
  it('preserves saved Watch selection and Ride readings without rewriting them on load', async () => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    preferences.workoutOptions.useWatch = true;
    preferences.views.ride.numbers = ['humanPowerW', 'cadenceRpm', 'heartRateBpm', 'speedMps'];
    await storage.save(preferences);
    const original = fs.files.get(file(1));
    expect((await (await native()).load()).preferences).toEqual(preferences);
    expect([...fs.files.values()]).toEqual([original]);
  });
  it('commits successive complete revisions without overwriting and retains two backups', async () => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    await storage.save(preferences);
    preferences.activeView = 'battery';
    await storage.save(preferences);
    preferences.views.battery.numbers = ['motorTempC'];
    await storage.save(preferences);
    expect([...fs.files.keys()].sort()).toEqual([file(2), file(3)]);
    expect(fs.moves).toEqual([undefined, undefined, undefined]);
    expect((await storage.load()).preferences.views.battery.numbers).toEqual(['motorTempC']);
    expect(JSON.parse(fs.files.get(file(2))!).views.battery.numbers).toEqual(
      defaultMonitorPreferences().views.battery.numbers,
    );
  });
  it('retains the old commit if writing or renaming the next revision fails', async () => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    await storage.save(preferences);
    const original = fs.files.get(file(1));
    preferences.activeView = 'temperature';
    fs.failWrite = true;
    await expect(storage.save(preferences)).rejects.toThrow(/^Power Log couldn't save this change\.$/);
    fs.failWrite = false;
    fs.failMove = true;
    await expect(storage.save(preferences)).rejects.toThrow(/^Power Log couldn't save this change\.$/);
    expect(fs.files.get(file(1))).toBe(original);
    expect((await storage.load()).preferences.activeView).toBe('ride');
    fs.failMove = false;
    await storage.save(preferences);
    expect((await storage.load()).preferences.activeView).toBe('temperature');
  });
  it('ignores torn temporary writes and reports recovery from a damaged newest commit', async () => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    await storage.save(preferences);
    preferences.activeView = 'battery';
    await storage.save(preferences);
    fs.files.set(file(3, 'tmp'), '{torn');
    expect((await storage.load()).preferences.activeView).toBe('battery');
    fs.files.set(file(2), '{corrupt');
    expect(await storage.load()).toMatchObject({
      preferences: { activeView: 'ride' },
      warning: expect.stringContaining('Recovered'),
    });
    fs.files.set(file(1), '{corrupt');
    await expect(storage.load()).rejects.toThrow('could not be read');
  });
  it('loads a revision holding removed Health choices without them and keeps saving', async () => {
    const storage = await native();
    await storage.save(defaultMonitorPreferences());
    fs.files.set(
      file(1),
      JSON.stringify({
        ...defaults,
        distanceSource: 'health:watch',
        views: {
          ...defaults.views,
          ride: { ...defaults.views.ride, charts: ['humanPowerW', 'healthSpeedMps', 'speedRaw', 'bogus'] },
        },
      }),
    );
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    expect(controller.snapshot()).toMatchObject({ ready: true, error: null });
    expect(controller.snapshot().preferences.distanceSource).toBe('auto');
    expect(controller.snapshot().preferences.views.ride.charts).toEqual(['humanPowerW']);
    controller.setSpeedUnit('mph');
    await controller.flush();
    expect(fs.files.size).toBe(2);
    expect(JSON.parse(fs.files.get(file(2))!)).toMatchObject({
      speedUnit: 'mph',
      distanceSource: 'auto',
      views: { ride: { charts: ['humanPowerW'] } },
    });
  });
  it('serializes concurrent saves and snapshots each request before the caller changes it', async () => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    const first = storage.save(preferences);
    preferences.activeView = 'battery';
    const second = storage.save(preferences);
    preferences.activeView = 'temperature';
    await Promise.all([first, second]);
    expect(JSON.parse(fs.files.get(file(1))!).activeView).toBe('ride');
    expect((await storage.load()).preferences.activeView).toBe('battery');
  });

  it('loads pending saves across controller remounts before applying new edits', async () => {
    const storage = await native(),
      first = new MonitorPreferencesController(storage);
    await first.hydrate();
    first.setSpeedUnit('mph');
    first.setSampleHz(8);
    expect(fs.files.size).toBe(0);
    const remounted = new MonitorPreferencesController(storage);
    const hydration = remounted.hydrate();
    remounted.selectView('battery');
    await hydration;
    await remounted.flush();
    await first.flush();
    expect(remounted.snapshot().preferences).toMatchObject({ speedUnit: 'mph', sampleHz: 8, activeView: 'battery' });
    expect((await storage.load()).preferences).toEqual(remounted.snapshot().preferences);
    expect(fs.moves).toHaveLength(3);
  });

  it.each(invalidEnvelopes)('recovers a previous revision when the newest envelope has $name', async ({ value }) => {
    const storage = await native(),
      preferences = defaultMonitorPreferences();
    preferences.activeView = 'battery';
    await storage.save(preferences);
    fs.files.set(file(2), JSON.stringify(value));
    const original = new Map(fs.files),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    await controller.flush();
    expect(controller.snapshot()).toMatchObject({
      preferences,
      ready: true,
      error: expect.stringContaining('Recovered'),
    });
    expect(fs.files).toEqual(original);
    fs.files.delete(file(1));
    await expect(storage.load()).rejects.toThrow('could not be read');
    expect(fs.files.get(file(2))).toBe(original.get(file(2)));
  });

  it('propagates failed commits through flush and permits the next save', async () => {
    const storage = await native(),
      controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    controller.setSpeedUnit('mph');
    await controller.flush();
    fs.failMove = true;
    controller.setSampleHz(8);
    await controller.flush();
    expect(controller.snapshot().error).toBe("Could not save monitor settings. Power Log couldn't save this change.");
    expect((await storage.load()).preferences).toMatchObject({ speedUnit: 'mph', sampleHz: 2 });
    fs.failMove = false;
    controller.selectView('battery');
    await controller.flush();
    expect(controller.snapshot().error).toBeNull();
    expect((await storage.load()).preferences).toMatchObject({ speedUnit: 'mph', sampleHz: 8, activeView: 'battery' });
  });

  it('shows plain text when the file system fails to load or save settings', async () => {
    const storage = await native();
    await storage.save(defaultMonitorPreferences());
    fs.failList = true;
    const unreadable = new MonitorPreferencesController(storage);
    await unreadable.hydrate();
    expect(unreadable.snapshot().error).toBe(
      "Could not load monitor settings. Saved monitor settings could not be read. Changes won't be saved until settings can be read.",
    );
    fs.failList = false;
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    fs.failWrite = true;
    controller.setSpeedUnit('mph');
    await controller.flush();
    expect(controller.snapshot().error).toBe("Could not save monitor settings. Power Log couldn't save this change.");
    for (const error of [unreadable.snapshot().error, controller.snapshot().error])
      expect(error).not.toMatch(/ExpoModulesCore|Caused by|function has failed/);
  });
});

describe('browser monitor preferences', () => {
  it.each(['denied read', 'malformed JSON'])(
    'keeps session edits without overwriting settings after %s',
    async failure => {
      const contents = failure === 'denied read' ? monitorPreferencesJson(defaultMonitorPreferences()) : '{broken';
      const getItem = vi.fn(() => {
        if (failure === 'denied read') throw new Error('Storage denied');
        return contents;
      });
      const setItem = vi.fn();
      vi.stubGlobal('localStorage', { getItem, setItem });
      const storage = (await import('../../src/services/monitor-preferences-store.web')).monitorPreferencesStore;
      const controller = new MonitorPreferencesController(storage);
      await controller.hydrate();
      const error = controller.snapshot().error;
      controller.selectView('battery');
      controller.setSpeedUnit('mph');
      await controller.flush();
      expect(controller.snapshot().preferences).toMatchObject({ activeView: 'battery', speedUnit: 'mph' });
      expect(controller.snapshot().error).toBe(error);
      expect(error).toContain("Changes won't be saved until settings can be read.");
      expect(setItem).not.toHaveBeenCalled();
      getItem.mockImplementation(() => monitorPreferencesJson(defaultMonitorPreferences()));
      const reloaded = new MonitorPreferencesController(storage);
      await reloaded.hydrate();
      expect(reloaded.snapshot().preferences).toEqual(defaultMonitorPreferences());
      expect(reloaded.snapshot().error).toBeNull();
      expect(setItem).not.toHaveBeenCalled();
    },
  );
  it('uses controller readings for first run and preserves existing Watch and reading choices', async () => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    });
    const { monitorPreferencesStore: storage, MONITOR_PREFERENCES_KEY: key } =
      await import('../../src/services/monitor-preferences-store.web');
    const preferences = (await storage.load()).preferences;
    expect(preferences.workoutOptions.useWatch).toBe(false);
    expect(preferences.views.ride.numbers).toEqual([
      'humanPowerW',
      'cadenceRpm',
      'motorInputPowerW',
      'controllerSpeedMps',
    ]);
    expect(values.size).toBe(0);
    preferences.workoutOptions.useWatch = true;
    preferences.views.ride.numbers = ['humanPowerW', 'cadenceRpm', 'heartRateBpm', 'speedMps'];
    const original = monitorPreferencesJson(preferences);
    values.set(key, original);
    expect((await storage.load()).preferences).toEqual(preferences);
    expect(values.get(key)).toBe(original);
  });
  it('refuses duplicate JSON fields without overwriting the unread browser store', async () => {
    const original = JSON.stringify(defaults).replace('"version":1', '"version":1,"version":1');
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    });
    const { monitorPreferencesStore: storage, MONITOR_PREFERENCES_KEY: key } =
      await import('../../src/services/monitor-preferences-store.web');
    values.set(key, original);
    const controller = new MonitorPreferencesController(storage);
    await controller.hydrate();
    expect(controller.snapshot().error).toContain('Invalid saved monitor settings');
    controller.setSampleHz(8);
    await controller.flush();
    expect(values.get(key)).toBe(original);
  });
  it('uses one validated localStorage value and preserves independently configured views', async () => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    });
    const { monitorPreferencesStore: storage, MONITOR_PREFERENCES_KEY: key } =
      await import('../../src/services/monitor-preferences-store.web');
    expect((await storage.load()).preferences.activeView).toBe('ride');
    const preferences = defaultMonitorPreferences();
    preferences.views.ride.charts = ['controllerTempC'];
    preferences.views.temperature.numbers = ['batteryCurrentA'];
    await storage.save(preferences);
    expect(values.size).toBe(1);
    expect(values.has(key)).toBe(true);
    expect((await storage.load()).preferences.views.ride.charts).toEqual(['controllerTempC']);
    expect((await storage.load()).preferences.views.temperature.numbers).toEqual(['batteryCurrentA']);
  });
  it('surfaces corrupt JSON, denied reads and quota errors as plain text', async () => {
    const getItem = vi.fn(() => '{broken'),
      setItem = vi.fn(() => {
        throw new DOMException("Failed to execute 'setItem' on 'Storage': exceeded the quota.", 'QuotaExceededError');
      });
    vi.stubGlobal('localStorage', { getItem, setItem });
    const storage = (await import('../../src/services/monitor-preferences-store.web')).monitorPreferencesStore;
    await expect(storage.load()).rejects.toThrow();
    getItem.mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    await expect(storage.load()).rejects.toThrow(/^Saved monitor settings could not be read\.$/);
    await expect(storage.save(defaultMonitorPreferences())).rejects.toThrow(/^This device is out of storage\.$/);
  });

  it.each(invalidEnvelopes)(
    'reports an invalid stored envelope with $name without hydration overwriting it',
    async ({ value }) => {
      const contents = JSON.stringify(value),
        getItem = vi.fn(() => contents),
        setItem = vi.fn();
      vi.stubGlobal('localStorage', { getItem, setItem });
      const storage = (await import('../../src/services/monitor-preferences-store.web')).monitorPreferencesStore;
      const controller = new MonitorPreferencesController(storage);
      const hydration = controller.hydrate();
      controller.setSpeedUnit('mph');
      await hydration;
      await controller.flush();
      expect(controller.snapshot()).toMatchObject({
        ready: true,
        error: expect.stringContaining('Invalid saved monitor settings'),
      });
      expect(controller.snapshot().preferences.speedUnit).toBe('mph');
      expect(setItem).not.toHaveBeenCalled();
      await expect(storage.load()).rejects.toThrow('Invalid saved monitor settings');
    },
  );
});

it.each([undefined, false, true])('persists only editable preferences and preserves GPS choice %s', async recordGPS => {
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  });
  const { monitorPreferencesStore: storage, MONITOR_PREFERENCES_KEY: key } =
    await import('../../src/services/monitor-preferences-store.web');
  const preferences = defaultMonitorPreferences();
  preferences.sampleHz = 8;
  preferences.workoutOptions.sampleHz = 4;
  if (recordGPS !== undefined) preferences.workoutOptions.recordGPS = recordGPS;
  await storage.save(preferences);
  const stored = JSON.parse(values.get(key)!);
  expect(stored.sampleHz).toBe(8);
  expect(stored.workoutOptions).not.toHaveProperty('sampleHz');
  for (const view of Object.values(stored.views))
    expect(Object.keys(view as object).sort()).toEqual(['charts', 'numbers', 'range', 'webColumns']);
  const loaded = (await storage.load()).preferences;
  expect(loaded.views.ride).toMatchObject({ id: 'ride', name: 'Ride' });
  expect(loaded.workoutOptions.recordGPS).toBe(recordGPS);
  expect(Object.hasOwn(stored.workoutOptions, 'recordGPS')).toBe(recordGPS !== undefined);
});
