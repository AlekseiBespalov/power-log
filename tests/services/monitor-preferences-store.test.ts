import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defaultMonitorPreferences } from '../../src/core/monitor';

const fs = vi.hoisted(() => ({ files: new Map<string, string>(), directories: new Set<string>(), failMove: false, failWrite: false, moves: [] as unknown[] }));
vi.mock('expo-file-system', () => ({
  Paths: { document: 'file:///documents' },
  Directory: class {
    uri: string;
    constructor(parent: string, name: string) { this.uri = `${parent}/${name}`; }
    get exists() { return fs.directories.has(this.uri); }
    create() { fs.directories.add(this.uri); }
    list() { return [...fs.files.keys()].filter(uri => uri.startsWith(`${this.uri}/`)).map(uri => ({ name: uri.slice(this.uri.length + 1) })); }
  },
  File: class {
    uri: string;
    constructor(parent: { uri: string }, name: string) { this.uri = `${parent.uri}/${name}`; }
    get size() { return fs.files.get(this.uri)?.length ?? 0; }
    textSync() { const value = fs.files.get(this.uri); if (value === undefined) throw new Error('Missing file'); return value; }
    write(contents: string) { if (fs.failWrite) throw new Error('Write failed'); fs.files.set(this.uri, contents); }
    moveSync(destination: { uri: string }, options?: unknown) {
      fs.moves.push(options);
      if (fs.failMove) throw new Error('Rename failed');
      if (fs.files.has(destination.uri)) throw new Error('Destination exists');
      fs.files.set(destination.uri, this.textSync()); fs.files.delete(this.uri); this.uri = destination.uri;
    }
    delete() { fs.files.delete(this.uri); }
  },
}));
beforeEach(() => { vi.resetModules(); fs.files.clear(); fs.directories.clear(); fs.moves = []; fs.failMove = false; fs.failWrite = false; });
afterEach(() => { vi.unstubAllGlobals(); });
const native = async () => (await import('../../src/services/monitor-preferences-store.native')).monitorPreferencesStore;
const file = (revision: number, suffix = 'json') => `file:///documents/power-log-preferences/monitor-${String(revision).padStart(16, '0')}.${suffix}`;

describe('native monitor preference commits', () => {
  it('loads defaults without creating a preferences file', async () => {
    expect((await (await native()).load()).preferences).toEqual(defaultMonitorPreferences());
    expect(fs.files.size).toBe(0); expect(fs.directories.size).toBe(0);
  });
  it('commits successive complete revisions without overwriting and retains two backups', async () => {
    const storage = await native(), preferences = defaultMonitorPreferences();
    await storage.save(preferences); preferences.activeView = 'battery';
    await storage.save(preferences); preferences.views.battery.numbers = ['motorTempC']; await storage.save(preferences);
    expect([...fs.files.keys()].sort()).toEqual([file(2), file(3)]);
    expect(fs.moves).toEqual([undefined, undefined, undefined]);
    expect((await storage.load()).preferences.views.battery.numbers).toEqual(['motorTempC']);
    expect(JSON.parse(fs.files.get(file(2))!).views.battery.numbers).toEqual(defaultMonitorPreferences().views.battery.numbers);
  });
  it('retains the old commit if writing or renaming the next revision fails', async () => {
    const storage = await native(), preferences = defaultMonitorPreferences(); await storage.save(preferences);
    const original = fs.files.get(file(1)); preferences.activeView = 'temperature';
    fs.failWrite = true; await expect(storage.save(preferences)).rejects.toThrow('Write failed');
    fs.failWrite = false; fs.failMove = true; await expect(storage.save(preferences)).rejects.toThrow('Rename failed');
    expect(fs.files.get(file(1))).toBe(original);
    expect((await storage.load()).preferences.activeView).toBe('ride');
    fs.failMove = false; await storage.save(preferences);
    expect((await storage.load()).preferences.activeView).toBe('temperature');
  });
  it('ignores torn temporary writes and reports recovery from a damaged newest commit', async () => {
    const storage = await native(), preferences = defaultMonitorPreferences(); await storage.save(preferences);
    preferences.activeView = 'battery'; await storage.save(preferences);
    fs.files.set(file(3, 'tmp'), '{torn');
    expect((await storage.load()).preferences.activeView).toBe('battery');
    fs.files.set(file(2), '{corrupt');
    expect(await storage.load()).toMatchObject({ preferences: { activeView: 'ride' }, warning: expect.stringContaining('Recovered') });
    fs.files.set(file(1), '{corrupt'); await expect(storage.load()).rejects.toThrow('could not be read');
  });
  it('serializes concurrent saves and snapshots each request before the caller changes it', async () => {
    const storage = await native(), preferences = defaultMonitorPreferences();
    const first = storage.save(preferences); preferences.activeView = 'battery';
    const second = storage.save(preferences); preferences.activeView = 'temperature';
    await Promise.all([first, second]);
    expect(JSON.parse(fs.files.get(file(1))!).activeView).toBe('ride');
    expect((await storage.load()).preferences.activeView).toBe('battery');
  });
});

describe('browser monitor preferences', () => {
  it('uses one validated localStorage value and preserves independently configured views', async () => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value) });
    const { monitorPreferencesStore: storage, MONITOR_PREFERENCES_KEY: key } = await import('../../src/services/monitor-preferences-store.web');
    expect((await storage.load()).preferences.activeView).toBe('ride');
    const preferences = defaultMonitorPreferences(); preferences.views.ride.charts = ['controllerTempC', 'controllerTempC', 'unknown'];
    preferences.views.temperature.numbers = ['batteryCurrentA']; await storage.save(preferences);
    expect(values.size).toBe(1); expect(values.has(key)).toBe(true);
    expect((await storage.load()).preferences.views.ride.charts).toEqual(['controllerTempC']);
    expect((await storage.load()).preferences.views.temperature.numbers).toEqual(['batteryCurrentA']);
  });
  it('surfaces corrupt JSON, denied reads and quota errors', async () => {
    const getItem = vi.fn(() => '{broken'), setItem = vi.fn(() => { throw new Error('Quota exceeded'); });
    vi.stubGlobal('localStorage', { getItem, setItem });
    const storage = (await import('../../src/services/monitor-preferences-store.web')).monitorPreferencesStore;
    await expect(storage.load()).rejects.toThrow();
    getItem.mockImplementation(() => { throw new Error('Storage denied'); });
    await expect(storage.load()).rejects.toThrow('Storage denied');
    await expect(storage.save(defaultMonitorPreferences())).rejects.toThrow('Quota exceeded');
  });
});
