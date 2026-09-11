/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { compileAnalysis } from '../src/analysis/analysisCompiler.js';
import { expect, it, vi } from 'vitest';
import {
  aggregation,
  filter,
  FilterOperator,
  AggregationGroupType,
  AggregationFunction,
  AggregationDateUnit,
} from '@ahoo-wang/fetcher-wow';
import {
  validateViewDefinition,
  validateViewInstance,
  createFilterConfiguration,
  ViewEngine,
} from '../src/index.js';
import type { ViewDefinition, AnalysisViewInstance } from '../src/index.js';
const definition: ViewDefinition = {
  id: 'analysis',
  title: 'Analysis',
  sourceId: 'source',
  fields: [{ field: 'state', label: 'State', type: 'string' }],
  analysis: {
    count: true,
    fields: [
      { field: 'state', groups: [AggregationGroupType.TERMS], functions: [] },
    ],
  },
};
const instance: AnalysisViewInstance = {
  id: 'a',
  definitionId: 'analysis',
  title: 'A',
  kind: 'analysis',
  scope: { type: 'personal' },
  revision: '1',
  config: {
    dimensions: [],
    metrics: [
      {
        id: 'count',
        alias: 'n',
        title: 'Count',
        component: { name: 'count' },
        props: {},
      },
    ],
    sort: [],
    limit: 100,
    filters: createFilterConfiguration({
      id: 'all',
      component: { name: 'builtin' },
      operator: FilterOperator.MATCH_ALL,
      props: {},
    }),
    presentation: { layout: 'table', columns: [] },
  },
};
it.each([
  { fields: [null] },
  { fields: [{ field: 'state' }] },
  { fields: [{ field: 'state', groups: ['wrong'], functions: [] }] },
  { fields: [{ field: 'state', groups: [], functions: ['wrong'] }] },
  {
    fields: [
      { field: 'state', groups: [], functions: [], dateUnits: ['wrong'] },
    ],
  },
  { fields: [{ field: 'state', groups: [], functions: [], any: 1 }] },
  { expressions: 1 },
  { scopes: {} },
  { scopes: [null] },
  { limits: { maxGroups: 33 } },
  { limits: { maxMetrics: 0 } },
  { limits: { maxSort: '32' } },
  { limits: { maxLimit: Infinity } },
  {
    scopes: [
      {
        id: 'x',
        label: 'X',
        elements: [],
        fields: [],
        capability: { count: true, fields: [] },
      },
    ],
  },
  {
    fields: [
      { field: 'state', groups: [], functions: [] },
      { field: 'state', groups: [], functions: [] },
    ],
  },
])('rejects malformed analysis capability before publication: %j', patch => {
  expect(() =>
    validateViewDefinition({
      ...definition,
      analysis: { ...definition.analysis, ...patch },
    }),
  ).toThrow();
});
it('admits scoped numeric capabilities with formatting and tightened limits', () => {
  const numeric = {
    field: 'amount',
    groups: [AggregationGroupType.HISTOGRAM],
    functions: [AggregationFunction.SUM],
    dateUnits: [AggregationDateUnit.DAY],
    any: true,
    unit: 'CNY',
    numberFormat: { maximumFractionDigits: 2 },
  };
  expect(() =>
    validateViewDefinition({
      ...definition,
      analysis: {
        count: true,
        fields: [numeric],
        expressions: true,
        limits: { maxGroups: 2, maxMetrics: 3 },
        scopes: [
          {
            id: 'items',
            label: 'Items',
            elements: [{ path: 'items', fields: [] }],
            fields: [],
            capability: { count: true, fields: [numeric] },
          },
        ],
      },
    }),
  ).not.toThrow();
});
it.each([
  { filters: 'bad' },
  { filters: { mode: 'simple', root: null } },
  { limit: {} },
  { limit: null },
  { scope: { id: 'items', filters: ['bad'] } },
  { dimensions: [null] },
  { metrics: ['bad'] },
  { metrics: [{ id: 'x', alias: 'x', title: 'X', props: {} }] },
  { metrics: [{ ...instance.config.metrics[0], props: null }] },
  { sort: [null] },
  { sort: [{ alias: 'n', direction: 'wrong' }] },
  { scope: { id: 'x', filters: null } },
  { dimensions: [{ ...instance.config.metrics[0], label: null }] },
])(
  'rejects malformed analysis components even with semantic validation disabled: %j',
  patch => {
    expect(() =>
      validateViewInstance(
        { ...instance, config: { ...instance.config, ...patch } },
        definition,
        undefined,
        false,
      ),
    ).toThrow();
  },
);
it('keeps structurally valid incomplete drafts recoverable', () => {
  expect(() =>
    validateViewInstance(
      {
        ...instance,
        config: {
          ...instance.config,
          limit: '-',
          metrics: [
            {
              ...instance.config.metrics[0],
              component: { name: 'unknown-extension' },
              title: '',
              alias: '',
            },
          ],
        },
      },
      definition,
      undefined,
      false,
    ),
  ).not.toThrow();
});
it('snapshots analysis compiler functions rather than retaining mutable entries', () => {
  const original = () => aggregation.count('n');
  const compiler = { roles: ['metric' as const], compile: original };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    host: {},
    analysisCompilers: { custom: compiler },
  });
  try {
    compiler.compile = () => aggregation.count('changed');
    compiler.roles.length = 0;
    expect(engine.analysisCompilers.custom.roles).toEqual(['metric']);
    expect(Object.isFrozen(engine.analysisCompilers.custom.roles)).toBe(true);
    expect(engine.analysisCompilers.custom.compile).toBe(original);
    expect(Object.isFrozen(engine.analysisCompilers.custom)).toBe(true);
  } finally {
    engine.dispose();
  }
});

it('retains the configuration size error across validity changes and blocks writes', async () => {
  const save = vi.fn(async () => {
    throw new Error('unexpected save');
  });
  const create = vi.fn(async () => {
    throw new Error('unexpected create');
  });
  const large = {
    ...instance,
    config: {
      ...instance.config,
      filters: createFilterConfiguration({
        id: 'all',
        component: { name: 'large' },
        operator: FilterOperator.MATCH_ALL,
        props: { data: 'x'.repeat(2048) },
      }),
    },
  };
  const engine = new ViewEngine({
    definitionId: definition.id,
    definition,
    instances: { instances: [large], defaultInstanceId: large.id },
    limits: { maxConfigBytes: 1024 },
    filterCompilers: { large: { compile: () => filter.matchAll() } },
    host: {
      resolveSource: () => ({ aggregate: async () => [{ n: 1 }] }),
      instance: { save, create },
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: false,
        }),
      },
    },
  });
  try {
    await engine.load();
    engine.setTitle('Changed');
    engine.analysis(large.id).setFilterValidity(false);
    engine.analysis(large.id).setFilterValidity(true);
    expect(
      engine
        .getSnapshot()
        .sessions[large.id].validation.some(
          issue => issue.id === 'config-size',
        ),
    ).toBe(true);
    await expect(engine.save(large.id)).rejects.toThrow();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }, large.id),
    ).rejects.toThrow();
    expect(save).not.toHaveBeenCalled();
    expect(create).not.toHaveBeenCalled();
    engine
      .analysis(large.id)
      .edit(config => ({ ...config, filters: instance.config.filters }));
    expect(
      engine
        .getSnapshot()
        .sessions[large.id].validation.some(
          issue => issue.id === 'config-size',
        ),
    ).toBe(false);
  } finally {
    engine.dispose();
  }
});

it.each(
  [undefined, [], ['unknown'], ['metric', 'metric'], 'metric'].map(roles => [
    roles,
  ]),
)('rejects invalid custom analysis roles at engine admission: %j', roles => {
  expect(
    () =>
      new ViewEngine({
        definitionId: definition.id,
        definition,
        host: {},
        analysisCompilers: {
          custom: { roles, compile: () => aggregation.count('n') },
        } as never,
      }),
  ).toThrow(/roles/);
});

it.each(['metrics', 'dimensions'] as const)(
  'rejects duplicate component identities across %s at structural admission',
  kind => {
    const value = structuredClone(instance);
    value.config[kind].push({ ...value.config.metrics[0], alias: 'other' });
    expect(() =>
      validateViewInstance(value, definition, undefined, false),
    ).toThrow(/ID.*重复/);
  },
);

it.each([5, 6])(
  'enforces the backend element limit at definition and compilation: %i',
  depth => {
    const scope = {
      id: 'nested',
      label: 'Nested',
      fields: [],
      capability: { fields: [], count: true },
      elements: Array.from({ length: depth }, () => ({
        path: 'lines',
        fields: [],
      })),
    };
    const capability = { ...definition.analysis!, scopes: [scope] };
    const validate = () =>
      validateViewDefinition({ ...definition, analysis: capability });
    const result = compileAnalysis(
      {
        ...instance.config,
        scope: {
          id: scope.id,
          filters: scope.elements.map(() => instance.config.filters),
        },
      },
      { fields: definition.fields, capability },
    );
    if (depth === 5) {
      expect(validate).not.toThrow();
      expect(result.plan).toBeDefined();
    } else {
      expect(validate).toThrow();
      expect(result.plan).toBeUndefined();
    }
  },
);
