import { presentationControllerSpeedNote } from '../../shared/js';
import type { NativeState } from './types';

/** Local peripheral IDs distinguish identical advertisements; the tag stays stable across scans. */
export function bikeDisplayName(device: { id?: string; name?: string; controllerModel?: string }): string {
  const name = device.controllerModel
    ? `CYC ${device.controllerModel}`
    : !device.name || /^(CYCMOTOR|CYC controller|CYC bike)$/i.test(device.name)
      ? 'CYC bike'
      : device.name;
  if (!device.id) return name;
  let hash = 2166136261;
  for (const char of device.id.toLowerCase()) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  const tag = (hash >>> 0).toString(16).padStart(8, '0').slice(-6).toUpperCase();
  return `${name} · ${tag}`;
}

export function controllerSpeedNote(
  identity: Pick<NativeState, 'controllerModel' | 'firmwareLabel' | 'controllerProtocol'>,
): string | null {
  return (
    presentationControllerSpeedNote(
      identity.controllerModel ?? null,
      identity.firmwareLabel ?? null,
      identity.controllerProtocol ?? null,
    ) ?? null
  );
}
