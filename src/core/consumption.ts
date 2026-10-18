export type BatteryConsumptionSource = 'controller' | 'calculated';
export const CONSUMPTION_METRICS = [
  'controllerEnergyWh',
  'controllerChargeAh',
  'calculatedEnergyWh',
  'calculatedChargeAh',
] as const;
export type ConsumptionMetric = (typeof CONSUMPTION_METRICS)[number];
export const isConsumptionMetric = (id: string): id is ConsumptionMetric =>
  (CONSUMPTION_METRICS as readonly string[]).includes(id);
export interface BatteryConsumptionSummary {
  source: BatteryConsumptionSource;
  energyWh: number | null;
  chargeAh: number | null;
  partial: boolean;
}
export const consumptionMetrics = (source: BatteryConsumptionSource) =>
  [`${source}EnergyWh`, `${source}ChargeAh`] as const;
