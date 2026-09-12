import { describe, expect, it } from 'vitest';
import { baselinePatch, withContent } from '../../src/engine/sessionState.js';
import { instance } from './fixtures.js';

describe('baselinePatch', () => {
  it('returns a record-discriminated patch carrying baseline and merged content', () => {
    const baseline = instance('mine');
    const local = { ...baseline, title: '本地标题' };
    const patch = baselinePatch(baseline, local);
    expect(patch).toEqual({
      kind: 'record',
      baseline,
      instance: withContent(baseline, local),
    });
    expect(patch.kind).toBe('record');
  });

  it('returns an analysis-discriminated patch for analysis instances', () => {
    const record = instance('agg');
    const baseline = {
      ...record,
      kind: 'analysis' as const,
      config: {
        filters: record.config.filters,
        dimensions: [],
        metrics: [],
        sort: [],
        limit: 100,
        presentation: { layout: 'table' as const, columns: [] },
      },
    };
    const patch = baselinePatch(baseline, baseline);
    expect(patch.kind).toBe('analysis');
    expect(patch.baseline).toBe(baseline);
  });
});
