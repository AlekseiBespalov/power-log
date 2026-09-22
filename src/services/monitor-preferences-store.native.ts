import { Directory, File, Paths } from 'expo-file-system';
import { defaultMonitorPreferences, validateMonitorPreferences, type MonitorPreferences } from '../core/monitor';
import type { MonitorPreferencesStore } from './monitor-preferences-store';

const root = () => new Directory(Paths.document, 'power-log-preferences');
const revisionPattern = /^monitor-(\d{16})\.json$/;
const revisions = (directory: Directory) => directory.list().map(file => ({ name: file.name, revision: Number(revisionPattern.exec(file.name)?.[1]) }))
  .filter(file => Number.isSafeInteger(file.revision) && file.revision > 0).sort((a, b) => b.revision - a.revision);
function read(directory: Directory, name: string): MonitorPreferences {
  const file = new File(directory, name);
  if (file.size > 64 * 1024) throw new Error('Monitor settings file is too large.');
  return validateMonitorPreferences(JSON.parse(file.textSync()));
}

let writes: Promise<void> = Promise.resolve();
export const monitorPreferencesStore: MonitorPreferencesStore = {
  async load() {
    await writes;
    const directory = root();
    if (!directory.exists) return { preferences: defaultMonitorPreferences() };
    const files = revisions(directory);
    for (const [index, file] of files.entries()) {
      try { return { preferences: read(directory, file.name), ...(index > 0 ? { warning: 'Recovered the previous monitor settings.' } : {}) }; }
      catch { /* A prior committed revision can recover an unreadable newest file. */ }
    }
    if (files.length) throw new Error('Saved monitor settings could not be read.');
    return { preferences: defaultMonitorPreferences() };
  },
  save(preferences) {
    const contents = JSON.stringify(validateMonitorPreferences(preferences));
    const pending = writes.then(() => {
      const directory = root();
      directory.create({ intermediates: true, idempotent: true });
      const revision = (revisions(directory)[0]?.revision ?? 0) + 1;
      if (!Number.isSafeInteger(revision)) throw new Error('Monitor settings revision limit reached.');
      const name = `monitor-${String(revision).padStart(16, '0')}`;
      const temporary = new File(directory, `${name}.tmp`);
      temporary.write(contents);
      // A same-directory rename to a new name commits a complete immutable revision.
      // Expo's overwrite option deletes the old target first, so never use it here.
      temporary.moveSync(new File(directory, `${name}.json`));
      let retained = 0;
      for (const file of revisions(directory)) {
        try {
          read(directory, file.name);
          if (++retained > 2) new File(directory, file.name).delete();
        } catch { /* Cleanup must not turn a committed save into a failed save. */ }
      }
    });
    writes = pending.catch(() => {});
    return pending;
  },
};
