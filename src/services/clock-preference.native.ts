import bridge from '../../modules/power-log-bridge';

export function deviceUsesTwentyFourHourClock(): boolean | null {
  return bridge?.usesTwentyFourHourClock() ?? null;
}
