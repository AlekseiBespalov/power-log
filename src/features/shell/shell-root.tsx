import { useEffect, useState, type ReactNode } from 'react';
import { Platform } from 'react-native';
import { createExportEntries, powerLogClient } from '../../../modules/power-log';
import { version } from '../../../package.json';
import type { UiEnv } from '../../components/screen-model';
import { createUiPlatform } from '../../components/ui-platform';
import { UiEnvProvider, useForeground, useModelSnapshot } from '../../components/use-screen-model';
import { BuildMismatchScreen } from './build-mismatch-screen';
import { AppShellModel } from './shell-model';

function createUiEnv(): UiEnv {
  const client = powerLogClient();
  const platform = Platform.OS === 'web' ? 'web' : Platform.OS === 'ios' ? 'ios' : 'android';
  const ui = createUiPlatform();
  const entries = createExportEntries(client, { platform, appVersion: version });
  return { client, ui, shell: new AppShellModel(client, ui, platform), exports: entries, diagnostics: entries };
}

export function ShellRoot({ children }: { children: ReactNode }) {
  const [env] = useState(createUiEnv);
  const foreground = useForeground(env);
  useEffect(() => {
    if (!foreground) return;
    env.shell.activate();
    return () => env.shell.deactivate();
  }, [env, foreground]);
  const state = useModelSnapshot(env.shell);
  return (
    <UiEnvProvider value={env}>
      {state.mode === 'mismatch' && state.message !== null ? <BuildMismatchScreen message={state.message} /> : children}
    </UiEnvProvider>
  );
}
