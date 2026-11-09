import { describe, expect, it } from 'vitest';
import { archivedBinaries, symbolicationPlan } from '../../scripts/symbolicate-ios.mjs';

describe('symbolicate-ios', () => {
  const uuids =
    'UUID: CE536DE9-302D-33C5-9B8C-9FA1AD688AE3 (arm64) /releases/0.7.0-700-abc1234/ios/dSYMs/PowerLog.app.dSYM/Contents/Resources/DWARF/PowerLog\n' +
    'UUID: 11111111-2222-3333-4444-555555555555 (arm64_32) /releases/0.7.0-700-abc1234/ios/dSYMs/PowerLogWatch.app.dSYM/Contents/Resources/DWARF/PowerLogWatch\n';

  it('symbolicates crash and unexpected lines whose image an archived dSYM carries', () => {
    const log = [
      JSON.stringify({ v: 2, event: 'launch', level: 'info' }),
      JSON.stringify({
        v: 2,
        event: 'crash',
        exception: 'IllegalStateException',
        image: 'ce536de9-302d-33c5-9b8c-9fa1ad688ae3',
        load: '0x100000000',
        frames: ['0x100012a4c', '0x1000130f0'],
        thread: 'ride',
      }),
      JSON.stringify({
        v: 2,
        event: 'unexpected',
        exception: 'X',
        image: 'AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA',
        load: '0x1',
        frames: ['0x2'],
        component: 'bridge',
      }),
      JSON.stringify({
        v: 2,
        event: 'unexpected',
        exception: 'Y',
        frames: ['app.Foo.bar(Foo.kt:3)'],
        component: 'host',
      }),
      '{"v":2,"event":"crash"',
    ].join('\n');

    expect(
      symbolicationPlan(log, archivedBinaries([{ folder: '/releases/0.7.0-700-abc1234/ios/dSYMs', text: uuids }])),
    ).toEqual([
      {
        title: 'crash IllegalStateException ride',
        image: 'ce536de9-302d-33c5-9b8c-9fa1ad688ae3',
        atos: [
          'atos',
          '-arch',
          'arm64',
          '-o',
          '/releases/0.7.0-700-abc1234/ios/dSYMs/PowerLog.app.dSYM/Contents/Resources/DWARF/PowerLog',
          '-l',
          '0x100000000',
          '0x100012a4c',
          '0x1000130f0',
        ],
      },
      { title: 'unexpected X bridge', image: 'AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA', atos: null },
    ]);
  });

  it('finds the binaries inside the folder that holds uuids.txt after the folder moves', () => {
    const binaries = archivedBinaries([
      { folder: '/Volumes/Backup/releases/0.7.0-700-abc1234/ios/dSYMs', text: uuids },
    ]);

    expect(binaries.get('11111111-2222-3333-4444-555555555555')).toEqual({
      arch: 'arm64_32',
      path: '/Volumes/Backup/releases/0.7.0-700-abc1234/ios/dSYMs/PowerLogWatch.app.dSYM/Contents/Resources/DWARF/PowerLogWatch',
    });
  });
});
