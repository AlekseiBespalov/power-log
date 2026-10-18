const requireFile = suffix => {
  const matches = [...globalThis.__r.getModules()].filter(
    ([, module]) => module.verboseName === suffix || module.verboseName?.endsWith(`/${suffix}`),
  );
  if (matches.length !== 1) throw new Error(`Expected one Metro module: ${suffix}`);
  return globalThis.__r(matches[0][0]);
};
const { workouts } = requireFile('src/services/workouts.native.ts');
const { nativeMonitorSource } = requireFile('src/services/monitor-source.native.ts');
const rideID = '<printed-ride-id>';
const timed = async (label, operation) => {
  const began = performance.now();
  const value = await operation();
  console.log(JSON.stringify({ label, acceptedMs: performance.now() - began }));
  return value;
};

globalThis.measureSummary = async () => {
  const detail = await timed('summary-cold', () => workouts.read(rideID, 'auto'));
  console.log(JSON.stringify(detail));
  await timed('summary-warm', () => workouts.read(rideID, 'auto'));
};

const source = nativeMonitorSource('workout', rideID, false, 'auto');
let generation = 1;
const firstOpen = async () => {
  const g = generation++;
  const description = await source.describeSource({ generation: g });
  const accept = result => {
    if (
      result.status !== 'ok' ||
      result.generation !== g ||
      result.sourceId !== description.sourceId ||
      result.revision !== description.revision
    )
      throw new Error('Unaccepted monitor result');
    return result;
  };
  accept(description);
  const request = {
    generation: g,
    expectedRevision: description.revision,
    metrics: ['humanPowerW', 'motorInputPowerW', 'cadenceRpm', 'heartRateBpm', 'distanceMeters'],
    startSeconds: description.domain.start,
    endSeconds: description.domain.end,
  };
  const plot = accept(await source.readPlot({ ...request, buckets: 360 }));
  const stats = accept(await source.rangeStats(request));
  return { description, plot, stats };
};
globalThis.measureChart = async () => {
  const chart = await timed('chart-first-open-cold', firstOpen);
  console.log(JSON.stringify(chart));
  await timed('chart-first-open-warm', firstOpen);
};
