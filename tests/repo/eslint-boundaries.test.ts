import { ESLint } from 'eslint';
import { beforeAll, describe, expect, it } from 'vitest';

const eslint = new ESLint({ cwd: process.cwd() });
async function restricted(filePath: string, source: string) {
  const [result] = await eslint.lintText(source, { filePath });
  return result!.messages.filter(message => message.ruleId === 'no-restricted-imports').length;
}

describe('ESLint boundary rows', { timeout: 30_000 }, () => {
  beforeAll(async () => {
    await restricted('src/features/history/boundary-probe.ts', '');
  }, 120_000);

  it('keeps the UI off Expo, shared Kotlin and the module internals', async () => {
    const ui = 'src/features/history/boundary-probe.ts';
    expect(await restricted(ui, "import { kit } from '../../../shared/generated/js/kit';\nkit();\n")).toBe(1);
    expect(await restricted(ui, "import { kit } from '../../../shared/generated/ts/api';\nkit();\n")).toBe(1);
    expect(
      await restricted(ui, "import { requireNativeModule } from 'expo-modules-core';\nrequireNativeModule();\n"),
    ).toBe(1);
    expect(await restricted(ui, "import { client } from '../../../modules/power-log/src/client';\nclient();\n")).toBe(
      1,
    );
    expect(await restricted(ui, "import module from '../../../modules/power-log';\nmodule();\n")).toBe(0);
  });

  it('lets today’s files load only what they import today', async () => {
    const component = 'src/components/route-paths.ts';
    expect(await restricted(component, "import { kit } from '../../shared/generated/js/kit';\nkit();\n")).toBe(1);
    expect(await restricted(component, "import { web } from '../../shared/generated/js/web';\nweb();\n")).toBe(1);
    const native = "import { requireNativeViewManager } from 'expo-modules-core';\nrequireNativeViewManager();\n";
    expect(await restricted('src/components/charts/native-raster-chart.native.tsx', native)).toBe(1);
    expect(await restricted('modules/power-log/src/chart-raster-view.native.ts', native)).toBe(0);
  });

  it('keeps the module off the UI and each Kotlin/JS build in its own files', async () => {
    const ui = "import { HistoryScreen } from '../../../src/features/history/history-screen';\nHistoryScreen();\n";
    expect(await restricted('modules/power-log/src/client.ts', ui)).toBe(1);
    expect(
      await restricted('modules/power-log/index.ts', "import { kit } from '../../shared/generated/js/kit';\nkit();\n"),
    ).toBe(1);
    const native = 'modules/power-log/src/kit.native.ts';
    expect(await restricted(native, "import { kit } from '../../../shared/generated/js/kit';\nkit();\n")).toBe(0);
    expect(await restricted(native, "import { web } from '../../../shared/generated/js/web';\nweb();\n")).toBe(1);
    const web = 'modules/power-log/src/transport.web.ts';
    expect(await restricted(web, "import { web } from '../../../shared/generated/js/web';\nweb();\n")).toBe(0);
    expect(await restricted(web, "import { kit } from '../../../shared/generated/js/kit';\nkit();\n")).toBe(1);
    expect(
      await restricted(web, "import { requireNativeModule } from 'expo-modules-core';\nrequireNativeModule();\n"),
    ).toBe(1);
  });

  it('keeps the export encoders plain TypeScript over the generated types', async () => {
    const encoder = 'modules/power-log/src/exports/fit.ts';
    expect(
      await restricted(encoder, "import { tables } from '../../../../shared/generated/ts/export-tables';\ntables();\n"),
    ).toBe(0);
    expect(await restricted(encoder, "import { kit } from '../../../../shared/generated/js/kit';\nkit();\n")).toBe(1);
    expect(await restricted(encoder, "import { View } from 'react-native';\nView();\n")).toBe(1);
    expect(await restricted(encoder, "import { File } from 'expo-file-system';\nFile();\n")).toBe(1);
  });
});
