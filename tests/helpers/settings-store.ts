import type { Page } from '@playwright/test';

type Range = 'seconds30' | 'seconds120' | 'seconds600' | 'all';
type View = 'ride' | 'battery' | 'temperature';
type Preset = { numbers?: string[]; charts?: string[]; range?: Range; webColumns?: number };

export type StoredSettings = {
  ride?: Partial<Record<'indoor' | 'useWatch' | 'saveToHealth' | 'recordGps', boolean>> &
    Partial<Record<'sampleRate' | 'distance' | 'battery', string>>;
  app?: {
    units?: 'metric' | 'imperial';
    temperature?: 'celsius' | 'fahrenheit';
    fitPower?: string;
    views?: Partial<Record<View, Preset>>;
    activeView?: View;
    historyView?: View;
    historyRange?: Range;
  };
};

export async function seedSettings(page: Page, settings: StoredSettings | string, path = '/sessions') {
  await page.goto('/settings');
  await page.getByRole('button', { name: /^Units, / }).waitFor();
  await page.evaluate(
    async text => {
      const name = (await indexedDB.databases())
        .map(database => database.name)
        .find(database => database?.startsWith('power-log-archive-'))!;
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open(name);
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      try {
        await new Promise<void>((resolve, reject) => {
          const transaction = db.transaction('appState', 'readwrite');
          const store = transaction.objectStore('appState');
          const read = store.get('app');
          read.onsuccess = () => store.put({ ...(read.result ?? { k: 'app', historyRevision: 0 }), settings: text });
          transaction.oncomplete = () => resolve();
          transaction.onerror = () => reject(transaction.error);
        });
      } finally {
        db.close();
      }
    },
    typeof settings === 'string' ? settings : JSON.stringify(settings),
  );
  await page.goto(path);
}

export async function storedSettings(page: Page): Promise<string | undefined> {
  return page.evaluate(async () => {
    const name = (await indexedDB.databases())
      .map(database => database.name)
      .find(database => database?.startsWith('power-log-archive-'))!;
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(name);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      return await new Promise<string | undefined>((resolve, reject) => {
        const read = db.transaction('appState').objectStore('appState').get('app');
        read.onsuccess = () => resolve((read.result as { settings?: string } | undefined)?.settings);
        read.onerror = () => reject(read.error);
      });
    } finally {
      db.close();
    }
  });
}
