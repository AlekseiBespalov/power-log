import type { Device } from './types';

/** Local peripheral IDs distinguish identical advertisements; the tag stays stable across scans. */
export function bikeDisplayName(device: { id?: string; name?: string; controllerModel?: string }): string {
  const name = device.controllerModel ? `CYC ${device.controllerModel}`
    : !device.name || /^(CYCMOTOR|CYC controller|CYC bike)$/i.test(device.name) ? 'CYC bike' : device.name;
  if (!device.id) return name;
  let hash = 2166136261;
  for (const char of device.id.toLowerCase()) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  const tag = (hash >>> 0).toString(16).padStart(8, '0').slice(-6).toUpperCase();
  return `${name} · ${tag}`;
}

export function bikeDetails(device: Device): string {
  const parts = [device.controllerModel ? `Firmware ${device.firmwareLabel ?? 'unknown'}` : 'Model identified on connection'];
  if (device.rssi < 0 && device.rssi >= -126) parts.push(`${device.rssi >= -65 ? 'Strong' : device.rssi >= -80 ? 'Fair' : 'Weak'} signal`);
  return parts.join(' · ');
}
