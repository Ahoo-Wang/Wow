/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  cleanup,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import { SearchBox } from '../src/ui/workbench/SearchBox.js';
import { MessagesProvider, zhCN } from '../src/ui/index.js';
import { afterEach, describe, expect, it } from 'vitest';
import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  builtinFieldKinds,
  defaultAnalysisConfig,
  isApproximate,
  MemoryViewStore,
  projectAnalysis,
  recordProjection,
  shapeChart,
  validateAnalysis,
  valueCandidatesConfig,
  ViewEngine,
  type AnalysisCapability,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import {
  useAnalysisEditor,
  useOpenView,
  useSearchBox,
} from '../src/react/index.js';
import {
  analysisCapability,
  analysisDefinition,
  analysisKernelConfig,
  errorCodes,
} from './fixtures/analysis.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import {
  describedField,
  ordersDescriptor,
  read,
} from './fixtures/descriptor.js';

afterEach(cleanup);

function definition(
  analysis: Partial<AnalysisCapability> = {},
): DataViewDefinition {
  return analysisDefinition({
    analysis: { ...analysisCapability, ...analysis },
  });
}

const validate = (
  analysis: Partial<AnalysisCapability>,
  config: Partial<AnalysisViewConfig>,
) =>
  errorCodes(
    validateAnalysis(
      definition(analysis),
      analysisKernelConfig(config),
      builtinFieldKinds,
    ),
  );

/** A descriptor of `analysisDefinition`'s fields that admits everything. */
function analysisDescriptor(
  overrides: Partial<QueryModelDescriptor> = {},
): QueryModelDescriptor {
  return ordersDescriptor({
    fields: ['warehouse', 'createdAt', 'amount'].map(path =>
      describedField(path),
    ),
    ...overrides,
  });
}

describe('4.4 the analysis rows that need a member of their own', () => {
  it('keeps no missing-value group where the source keeps none', () => {
    const { definition: narrowed, findings } = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        fields: [
          describedField('warehouse', {
            aggregate: {
              ...describedField('x').aggregate!,
              missingKey: false,
            },
          }),
          describedField('createdAt'),
          describedField('amount'),
        ],
      }),
      builtinFieldKinds,
    );

    expect(narrowed.analysis?.fields[0].missingKey).toBe(false);
    expect(findings).toContainEqual(
      expect.objectContaining({
        code: 'capability.analysis.field-narrowed',
        params: { field: 'warehouse', dropped: 'MISSING_KEY' },
      }),
    );
    // A fresh view's first dimension carries none, and a saved one that
    // does is refused.
    const fresh = defaultAnalysisConfig(narrowed);
    expect(fresh.groups[0]).not.toHaveProperty('missingKey');
    expect(
      errorCodes(
        validateAnalysis(
          narrowed,
          analysisKernelConfig({
            groups: [
              {
                type: 'TERMS',
                field: 'warehouse',
                alias: 'wh',
                missingKey: '(empty)',
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toContain('analysis.group.missing-key-unsupported');
  });

  it('fills no gaps where the source fills none', () => {
    const narrowed = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        analysis: { ...ordersDescriptor().analysis, dense: false },
      }),
      builtinFieldKinds,
    );
    expect(narrowed.definition.analysis?.dense).toBe(false);
    expect(narrowed.findings.map(found => found.code)).toContain(
      'capability.analysis.dense',
    );

    const dated = {
      groups: [
        {
          type: 'DATE_HISTOGRAM' as const,
          field: 'createdAt',
          alias: 'month',
          unit: 'MONTH' as const,
          dense: true,
        },
      ],
      sort: [],
      chart: { type: 'bar' as const },
      layout: 'table' as const,
    };
    expect(validate({ dense: false }, dated)).toContain(
      'analysis.group.dense-unsupported',
    );
    expect(validate({}, dated)).not.toContain(
      'analysis.group.dense-unsupported',
    );
  });

  it('orders groups by their dimensions only where the source sorts no metric', () => {
    const narrowed = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        analysis: {
          ...ordersDescriptor().analysis,
          sort: { groups: true, metrics: false },
        },
      }),
      builtinFieldKinds,
    );
    expect(narrowed.definition.analysis?.metricSort).toBe(false);

    // A metric in the sort is refused, a dimension is not.
    expect(validate({ metricSort: false }, {})).toContain(
      'analysis.sort.metric-unsupported',
    );
    expect(
      validate(
        { metricSort: false },
        { sort: [{ alias: 'wh', direction: 'ASC' }] },
      ),
    ).toEqual([]);
    // A fresh view and a condition's values start from a dimension.
    expect(defaultAnalysisConfig(narrowed.definition).sort).toEqual([
      { alias: 'warehouse_group', direction: 'ASC' },
    ]);
    expect(
      valueCandidatesConfig(
        narrowed.definition,
        narrowed.definition.fields[0],
        builtinFieldKinds,
      ).sort,
    ).toEqual([{ alias: 'value', direction: 'ASC' }]);
    // The table does not offer a metric header to sort by.
    const view = projectAnalysis(
      narrowed.definition,
      analysisKernelConfig({ sort: [] }),
      [{ wh: 'CN', orders: 2 }],
    );
    expect(view.columns.find(c => c.alias === 'orders')?.sortable).toBe(false);
    expect(view.columns.find(c => c.alias === 'wh')?.sortable).toBeUndefined();
  });

  it('keeps groups only by the metric types the source compares', () => {
    const narrowed = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        analysis: {
          ...ordersDescriptor().analysis,
          having: { metrics: ['COUNT'] as never },
        },
      }),
      builtinFieldKinds,
    );
    expect(narrowed.definition.analysis?.havingMetrics).toEqual(['COUNT']);

    const summed: Pick<AnalysisViewConfig, 'metrics'> = {
      metrics: [
        { type: 'COUNT' as const, alias: 'orders' },
        {
          type: 'NUMERIC' as const,
          alias: 'total',
          function: 'SUM' as const,
          expression: { type: 'FIELD' as const, field: 'amount' },
        },
      ],
    };
    const keep = (metric: string): Partial<AnalysisViewConfig> => ({
      ...summed,
      having: { type: 'CONDITION', metric, operator: 'GT', value: 1 },
    });
    expect(validate({ havingMetrics: ['COUNT'] }, keep('orders'))).toEqual([]);
    expect(validate({ havingMetrics: ['COUNT'] }, keep('total'))).toContain(
      'analysis.having.metric-unsupported',
    );
  });

  it('keeps a field out of a metric condition and out of a formula where the source does', () => {
    const narrowed = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        fields: [
          describedField('warehouse', {
            aggregate: {
              ...describedField('x').aggregate!,
              inMetricFilter: false,
            },
          }),
          describedField('createdAt'),
          describedField('amount', {
            aggregate: {
              ...describedField('x').aggregate!,
              expressionInput: false,
            },
          }),
        ],
      }),
      builtinFieldKinds,
    );
    const [warehouse, , amount] = narrowed.definition.analysis!.fields;
    expect(warehouse.inMetricFilter).toBe(false);
    expect(amount.expressionInput).toBe(false);

    const conditioned = validateAnalysis(
      narrowed.definition,
      analysisKernelConfig({
        metrics: [
          {
            type: 'COUNT',
            alias: 'orders',
            filter: {
              op: 'and',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
            },
          },
        ],
      }),
      builtinFieldKinds,
    );
    expect(errorCodes(conditioned)).toContain(
      'analysis.metric.filter-field-unsupported',
    );

    const formula = validateAnalysis(
      narrowed.definition,
      analysisKernelConfig({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'twice',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'FIELD', field: 'amount' },
            },
          },
        ],
        sort: [],
        layout: 'table',
      }),
      builtinFieldKinds,
    );
    expect(errorCodes(formula)).toContain(
      'analysis.expression.operand-unsupported',
    );
    // A sum of the field itself is no formula, and stays.
    expect(
      errorCodes(
        validateAnalysis(
          narrowed.definition,
          analysisKernelConfig({
            metrics: [
              {
                type: 'NUMERIC',
                alias: 'total',
                function: 'SUM',
                expression: { type: 'FIELD', field: 'amount' },
              },
            ],
            sort: [],
            layout: 'table',
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('marks as approximate what the source says it estimates, and nothing else', () => {
    const config = analysisKernelConfig({
      metrics: [
        {
          type: 'PERCENTILE',
          alias: 'p95',
          percentile: 95,
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          type: 'DISTINCT_COUNT',
          alias: 'kinds',
          expression: { type: 'FIELD', field: 'amount' },
        },
      ],
      sort: [],
    });
    const rows = [{ wh: 'CN', p95: 10, kinds: 3 }];
    const approximate = (analysis: DataViewDefinition) =>
      projectAnalysis(analysis, config, rows)
        .columns.filter(isApproximate)
        .map(column => column.alias);

    // Declared by nobody: percentiles, as Wow estimates them everywhere.
    expect(approximate(analysisDefinition())).toEqual(['p95']);
    // Elasticsearch estimates distinct counts too (#3489).
    const es = narrowDefinition(
      analysisDefinition(),
      analysisDescriptor({
        analysis: {
          ...ordersDescriptor().analysis,
          approximate: ['DISTINCT_COUNT', 'PERCENTILE'] as never,
        },
      }),
      builtinFieldKinds,
    );
    expect(approximate(es.definition)).toEqual(['p95', 'kinds']);
    // A source that computes both exactly marks neither, a boxplot included.
    const exact = definition({ approximate: [] });
    expect(approximate(exact)).toEqual([]);
    const box = analysisKernelConfig({
      metrics: [
        {
          type: 'NUMERIC',
          alias: 'lo',
          function: 'MIN',
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          type: 'PERCENTILE',
          alias: 'q1',
          percentile: 25,
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          type: 'PERCENTILE',
          alias: 'med',
          percentile: 50,
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          type: 'PERCENTILE',
          alias: 'q3',
          percentile: 75,
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          type: 'NUMERIC',
          alias: 'hi',
          function: 'MAX',
          expression: { type: 'FIELD', field: 'amount' },
        },
      ],
      sort: [],
      chart: {
        type: 'boxplot',
        boxplot: {
          category: 'wh',
          low: 'lo',
          q1: 'q1',
          median: 'med',
          q3: 'q3',
          high: 'hi',
        },
      },
    });
    const boxRows = [{ wh: 'CN', lo: 1, q1: 2, med: 3, q3: 4, hi: 5 }];
    expect(
      shapeChart(box, boxRows, undefined, { approximate: [] }),
    ).toMatchObject({
      approximate: false,
    });
    expect(shapeChart(box, boxRows)).toMatchObject({ approximate: true });
  });
});

describe('4.1 a field the source does not return', () => {
  it('is not asked for, and says so', () => {
    const { definition: narrowed, findings } = narrowDefinition(
      ordersDefinition(),
      ordersDescriptor({
        fields: ordersDescriptor().fields.map(field =>
          field.path === 'amount' ? { ...field, project: false } : field,
        ),
      }),
      builtinFieldKinds,
    );

    expect(findings).toContainEqual(
      expect.objectContaining({
        code: 'capability.field.not-projectable',
        params: { field: 'amount' },
      }),
    );
    expect(
      recordProjection(ordersDefinition(), recordConfig()).include,
    ).toContain('amount');
    expect(recordProjection(narrowed, recordConfig()).include).toEqual(['id']);
  });
});

describe('the controls on a narrowed analysis', () => {
  const saved: ViewInstance = {
    id: 'by-warehouse',
    definitionId: 'orders',
    title: 'By warehouse',
    scope: 'personal',
    revision: '1',
    config: analysisKernelConfig({ sort: [] }),
  };

  it('offer only what the source admits', async () => {
    const descriptor = analysisDescriptor({
      analysis: {
        ...ordersDescriptor().analysis,
        dense: false,
        sort: { groups: true, metrics: false },
        having: { metrics: ['COUNT'] as never },
        approximate: ['DISTINCT_COUNT', 'PERCENTILE'] as never,
      },
      fields: [
        describedField('warehouse', {
          aggregate: {
            ...describedField('x').aggregate!,
            missingKey: false,
            inMetricFilter: false,
          },
        }),
        describedField('createdAt'),
        describedField('amount', {
          aggregate: {
            ...describedField('x').aggregate!,
            expressionInput: false,
          },
        }),
      ],
    });
    const engine = new ViewEngine({
      definitions: [analysisDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () =>
        testSource({ describe: () => Promise.resolve(read(descriptor)) }),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'by-warehouse');
      return useAnalysisEditor(opened.runtime);
    });
    await waitFor(() => expect(result.current.fields.length).toBe(3));

    const option = (name: string) =>
      result.current.fields.find(entry => entry.field === name);
    expect(option('warehouse')?.missingKey).toBe(false);
    expect(option('amount')?.expressionInput).toBe(false);
    expect(result.current.conditionFields.map(f => f.name)).not.toContain(
      'warehouse',
    );
    expect(result.current).toMatchObject({
      denseAllowed: false,
      metricSortAllowed: false,
      havingMetrics: ['COUNT'],
      approximate: ['DISTINCT_COUNT', 'PERCENTILE'],
    });
  });

  it('offer everything the definition declares without a descriptor', async () => {
    const engine = new ViewEngine({
      definitions: [analysisDefinition()],
      store: new MemoryViewStore({ instances: [saved] }),
      resolveSource: () => testSource(),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'by-warehouse');
      return useAnalysisEditor(opened.runtime);
    });
    await waitFor(() => expect(result.current.fields.length).toBe(3));

    expect(result.current).toMatchObject({
      denseAllowed: true,
      metricSortAllowed: true,
      havingMetrics: null,
      approximate: ['PERCENTILE'],
    });
    expect(
      result.current.fields.every(field => field.expressionInput !== false),
    ).toBe(true);
  });
});

describe('a phrase search the source matches only as words', () => {
  it('says so in the search box', async () => {
    const searchable = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'q',
          label: 'Search notes',
          kind: 'search',
          searchMode: 'PHRASE',
        },
      ],
    });
    const open = async (modes: string[]) => {
      const base = ordersDescriptor();
      const descriptor = {
        ...base,
        record: { ...base.record, search: { modes, fields: [] } },
      } as QueryModelDescriptor;
      const engine = new ViewEngine({
        definitions: [searchable],
        store: new MemoryViewStore({ instances: [] }),
        resolveSource: () =>
          testSource({ describe: () => Promise.resolve(read(descriptor)) }),
      });
      const runtime = await engine.open('system:orders:all');
      if (runtime.kind === 'dashboard') throw new Error('a data view');
      return renderHook(() => useSearchBox(runtime)).result.current;
    };

    const words = await open(['TERMS']);
    expect(words?.byWords).toBe(true);
    expect((await open(['TERMS', 'PHRASE']))?.byWords).toBe(false);

    render(
      <MessagesProvider messages={zhCN}>
        <SearchBox search={words!} />
      </MessagesProvider>,
    );
    const box = screen.getByRole('searchbox', { name: 'Search notes' });
    expect(box.getAttribute('placeholder')).toBe('Search notes（按词检索）…');
  });
});
