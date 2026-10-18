import { describe, expect, it } from 'vitest';
import { MONITOR_METRICS, formatMetric, metricById } from '../../src/core/monitor';
import { coreMetricDeclarations } from '../../shared/js/power-log-core.mjs';
import type { CoreMetricDeclaration } from '../../shared/js/power-log-core';
import fixture from '../fixtures/contract/telemetry/metrics.json';

describe('core monitor catalog through the production monitor seam', () => {
  it('carries every contract declaration with battery input labels', () => {
    const expected = fixture.metrics.map(({ text: _text, ...metric }) => metric);
    const actual = MONITOR_METRICS.map(metric => ({ scale: 1, kind: null, zero: null, semantics: null, ...metric }));
    expect(actual).toEqual(expected);
  });
  it.each(fixture.metrics)('formats $id and unavailable readings through the core', metric => {
    expect(formatMetric(metricById(metric.id)!, fixture.value)).toBe(metric.text);
    for (const value of [null, undefined, NaN, Infinity])
      expect(formatMetric(metricById(metric.id)!, value)).toBe(fixture.unavailableText);
  });
  it.each(fixture.formatCases)('preserves the scalar formatting boundary $value', row => {
    expect(formatMetric(metricById(row.id)!, row.value)).toBe(row.expected);
  });
});

describe('core monitor catalog', () => {
  it('adapts every generated declaration field without changing the core metadata', () => {
    const expected = MONITOR_METRICS.map(metric => ({
      scale: 1,
      kind: null,
      zero: null,
      semantics: null,
      ...metric,
    }));
    const actual = coreMetricDeclarations().map((metric: CoreMetricDeclaration) => ({
      id: metric.id,
      label: metric.label,
      shortLabel: metric.shortLabel,
      unit: metric.unit,
      group: metric.group,
      color: metric.color,
      decimals: metric.decimals,
      gapSeconds: metric.gapSeconds,
      scale: metric.scale,
      kind: metric.kind,
      zero: metric.zero,
      semantics: metric.semantics,
      ...(metric.description == null ? {} : { description: metric.description }),
    }));
    expect(actual).toEqual(expected);
  });
});
