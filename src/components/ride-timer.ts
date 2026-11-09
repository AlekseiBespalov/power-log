import { kit, type PowerLogClient, type TimerView } from '../../modules/power-log';

export function timerText(timer: TimerView, client: PowerLogClient): string {
  if (timer.state === 'unavailable') return '—';
  const origin = timer.state === 'frozen' || timer.originMonoUs === null ? null : client.localTime(timer.originMonoUs);
  if (origin === null) return kit.formatDuration(timer.baseUs);
  return kit.formatDuration(Math.floor((performance.now() - origin) * 1000));
}
