/** CYC 5.3 selective field 23 is app_get_speed() in km/h, independent of display units. */
export function hasKnownControllerSpeedUnit(model: string, protocol: string): boolean {
  return (model === 'X6' || model === 'X12') && protocol === '5.3';
}
