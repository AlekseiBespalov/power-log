import { Directory, File, Paths } from 'expo-file-system';
import {
  defaultMonitorPreferences,
  monitorPreferencesJson,
  parseMonitorPreferences,
  type MonitorPreferences,
} from '../core/monitor';
import { failureCode, failurePresentation } from '../core/failures';
import type { MonitorPreferencesStore } from './monitor-preferences-store';

const root = () => new Directory(Paths.document, 'power-log-preferences');
const revisionPattern = /^monitor-(\d{16})\.json$/;
const revisions = (directory: Directory) =>
  directory
    .list()
    .map(file => ({ name: file.name, revision: Number(revisionPattern.exec(file.name)?.[1]) }))
    .filter(file => Number.isSafeInteger(file.revision) && file.revision > 0)
    .sort((a, b) => b.revision - a.revision);
function read(directory: Directory, name: string): MonitorPreferences {
  const file = new File(directory, name);
  if (file.size > 64 * 1024) throw new Error('Monitor settings file is too large.');
  return parseMonitorPreferences(file.textSync());
}

const unreadable = 'Saved monitor settings could not be read.';
function fileSystem<T>(work: () => T, failure: (error: unknown) => string): T {
  try {
    return work();
  } catch (error) {
    throw new Error(failure(error));
  }
}
const saveFailure = (error: unknown) => {
  const code = failureCode(error);
  return failurePresentation(code === 'unknown' ? 'storageFailed' : code).message;
};
const committedRevisions = (directory: Directory) => {
  try {
    return revisions(directory);
  } catch {
    return [];
  }
};

let writes: Promise<void> = Promise.resolve();
export const monitorPreferencesStore: MonitorPreferencesStore = {
  async load() {
    await writes;
    const directory = root();
    const files = fileSystem(
      () => (directory.exists ? revisions(directory) : null),
      () => unreadable,
    );
    if (!files) return { preferences: defaultMonitorPreferences() };
    for (const [index, file] of files.entries()) {
      try {
        return {
          preferences: read(directory, file.name),
          ...(index > 0 ? { warning: 'Recovered the previous monitor settings.' } : {}),
        };
      } catch {
        /* A prior committed revision can recover an unreadable newest file. */
      }
    }
    if (files.length) throw new Error(unreadable);
    return { preferences: defaultMonitorPreferences() };
  },
  save(preferences) {
    const contents = monitorPreferencesJson(preferences);
    const pending = writes.then(() => {
      const directory = root();
      const revision = fileSystem(() => {
        directory.create({ intermediates: true, idempotent: true });
        return (revisions(directory)[0]?.revision ?? 0) + 1;
      }, saveFailure);
      if (!Number.isSafeInteger(revision)) throw new Error('Monitor settings revision limit reached.');
      const name = `monitor-${String(revision).padStart(16, '0')}`;
      fileSystem(() => {
        const temporary = new File(directory, `${name}.tmp`);
        temporary.write(contents);
        // A same-directory rename to a new name commits a complete immutable revision.
        // Expo's overwrite option deletes the old target first, so never use it here.
        temporary.moveSync(new File(directory, `${name}.json`));
      }, saveFailure);
      let retained = 0;
      for (const file of committedRevisions(directory)) {
        try {
          read(directory, file.name);
          if (++retained > 2) new File(directory, file.name).delete();
        } catch {
          /* Cleanup must not turn a committed save into a failed save. */
        }
      }
    });
    writes = pending.catch(() => {});
    return pending;
  },
};
