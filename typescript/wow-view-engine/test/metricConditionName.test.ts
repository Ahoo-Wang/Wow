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

/**
 * What a metric with a condition of its own is called (D20 显示名): its
 * column title and, after a 「·」, the one value the condition keeps —
 * 「金额的总和 · 已发运」 — or 「… · 有条件」 when it keeps more than that. A
 * sum over the shipped orders used to wear the header of a sum over all of
 * them, and a region with none shipped read as a region with no sales.
 *
 * The reading is the kernel's (`metricCondition`), carried on the column
 * (`AnalysisColumnView.condition`) and worded by `columnTitle`; the tray's
 * `metricReference` reads the same kernel over the draft, so the card, the
 * sort, 「只保留」 and the result say one name. The gesture on screen is
 * test/metricCondition.test.tsx.
 */

import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  metricCondition,
  metricReferenceText,
  projectAnalysis,
  wordReferences,
  type AnalysisMetric,
  type DataViewDefinition,
  type FilterTree,
} from '../src/index.js';
import type { AnalysisFieldOption } from '../src/react/index.js';
import type { MessageFormatters, ViewMessages } from '../src/ui/index.js';
import { metricReference } from '../src/ui/analysis/editing.js';
import { columnTitle } from '../src/ui/display.js';
import { en } from '../src/ui/messages/en.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { formatMessage } from '../src/ui/messages.js';
import {
  analysisDefinition,
  analysisKernelConfig as config,
} from './fixtures/analysis.js';

function words(catalogue: ViewMessages): MessageFormatters {
  return {
    label: (key, params, fallback) => {
      const found = formatMessage(catalogue, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: () => '',
    issues: () => '',
  };
}
const EN = words(en);
const ZH = words(zhCN);

/** Orders, with a closed set of states and a free-text region beside them. */
function definition(): DataViewDefinition {
  const base = analysisDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'status',
        label: '状态',
        kind: 'enum',
        options: [
          { value: 'SHIPPED', label: '已发运' },
          { value: 'PAID', label: '已付款' },
        ],
      },
      { name: 'region', label: '地区', kind: 'string' },
      { name: 'rush', label: '加急', kind: 'boolean' },
    ],
  };
}
const FIELDS = definition().fields;

const where = (...children: FilterTree['children']): FilterTree => ({
  op: 'and',
  children,
});
const shipped = where({ field: 'status', operator: 'IN', value: ['SHIPPED'] });

function sum(filter?: FilterTree, label?: string): AnalysisMetric {
  return {
    type: 'NUMERIC',
    alias: 'total',
    function: 'SUM',
    expression: { type: 'FIELD', field: 'amount' },
    ...(filter ? { filter } : {}),
    ...(label ? { label } : {}),
  };
}

const reading = (filter: FilterTree) =>
  metricCondition(sum(filter), FIELDS, builtinFieldKinds);

describe('metricCondition', () => {
  // One value of one field names itself: an option by its label, a piece
  // of text as it is written.
  it('reads one value of one field as that value’s name', () => {
    expect(reading(shipped)?.value).toBe('已发运');
    expect(
      reading(where({ field: 'region', operator: 'EQ', value: '华东' }))?.value,
    ).toBe('华东');
    expect(
      reading(where({ field: 'region', operator: 'IN', value: ['华北'] }))
        ?.value,
    ).toBe('华北');
  });

  // Anything more than "the value is this one" keeps something the value
  // alone does not say — 「不等于 已发运」 is not 「已发运」 — and a number
  // or a yes after a 「·」 does not say what it is about.
  it('says only “conditioned” for anything else, and keeps the whole of it', () => {
    const several = [
      where({ field: 'status', operator: 'IN', value: ['SHIPPED', 'PAID'] }),
      where({ field: 'status', operator: 'NOT_IN', value: ['SHIPPED'] }),
      where({ field: 'region', operator: 'NE', value: '华东' }),
      where({ field: 'region', operator: 'CONTAINS', value: '华' }),
      where({ field: 'amount', operator: 'GT', value: 100 }),
      where({ field: 'rush', operator: 'EQ', value: true }),
      where(
        { field: 'status', operator: 'IN', value: ['SHIPPED'] },
        { field: 'region', operator: 'EQ', value: '华东' },
      ),
      {
        op: 'nor',
        children: [{ field: 'status', operator: 'IN', value: ['SHIPPED'] }],
      } satisfies FilterTree,
    ];
    for (const filter of several) {
      const found = reading(filter);
      expect(found).toBeDefined();
      expect(found?.value).toBeUndefined();
      expect(found?.items.length).toBeGreaterThan(0);
    }
  });

  // No filter, a filter nothing has been written into yet, a derived
  // metric and a reading without kinds all count every record as far as a
  // name can tell.
  it('is absent where the metric counts every record', () => {
    expect(metricCondition(sum(), FIELDS, builtinFieldKinds)).toBeUndefined();
    expect(reading(where())).toBeUndefined();
    expect(
      reading(where({ field: 'status', operator: 'IN', value: [] })),
    ).toBeUndefined();
    expect(metricCondition(sum(shipped), FIELDS, undefined)).toBeUndefined();
    expect(
      metricCondition(
        {
          type: 'DERIVED',
          alias: 'ratio',
          expression: { type: 'METRIC_REF', metric: 'total' },
        },
        FIELDS,
        builtinFieldKinds,
      ),
    ).toBeUndefined();
  });
});

describe('a conditioned column', () => {
  const project = (...metrics: [AnalysisMetric, ...AnalysisMetric[]]) =>
    projectAnalysis(
      definition(),
      config({ metrics, table: { columns: [] } }),
      [],
      undefined,
      builtinFieldKinds,
    );

  it('carries its condition, and says it after its title', () => {
    const view = project(sum(shipped));
    const column = view.columns.find(entry => entry.alias === 'total')!;
    expect(column.condition?.value).toBe('已发运');
    expect(columnTitle(column, ZH)).toBe('Amount的总和 · 已发运');
    expect(columnTitle(column, EN)).toBe('Sum of Amount · 已发运');
  });

  it('says “conditioned” when no one value names it', () => {
    const view = project(
      sum(where({ field: 'amount', operator: 'GT', value: 100 })),
    );
    const column = view.columns.find(entry => entry.alias === 'total')!;
    expect(columnTitle(column, ZH)).toBe('Amount的总和 · 有条件');
    expect(columnTitle(column, EN)).toBe('Sum of Amount · conditioned');
  });

  // A name the analyst typed is the whole title; the condition still rides
  // along for the header's description.
  it('lets a name the analyst gave win', () => {
    const view = project(sum(shipped, '发运额'));
    const column = view.columns.find(entry => entry.alias === 'total')!;
    expect(columnTitle(column, ZH)).toBe('发运额');
    expect(column.condition?.items).toHaveLength(1);
  });

  // The record count is counted under a condition as a sum is, and a
  // percentile keeps its 「≈」 in front.
  it('reads the same on a count and on a percentile', () => {
    const view = project(
      { type: 'COUNT', alias: 'orders', filter: shipped },
      {
        type: 'PERCENTILE',
        alias: 'p95',
        expression: { type: 'FIELD', field: 'amount' },
        percentile: 95,
        filter: shipped,
      },
    );
    const [count, p95] = ['orders', 'p95'].map(alias =>
      view.columns.find(entry => entry.alias === alias)!,
    );
    expect(columnTitle(count!, ZH)).toBe('记录数 · 已发运');
    expect(columnTitle(p95!, EN)).toMatch(/^≈ .+ · 已发运$/);
  });

  // Without kinds nothing reads the condition, and nothing is guessed.
  it('says nothing about a condition it was given no kinds to read', () => {
    const view = projectAnalysis(
      definition(),
      config({ metrics: [sum(shipped)], table: { columns: [] } }),
      [],
    );
    expect(view.columns.find(entry => entry.alias === 'total')?.condition).toBe(
      undefined,
    );
  });

  // A ratio over the shipped sum is a different ratio from one over the
  // whole sum, so the operand says its condition as its own column does.
  it('carries into a derived metric that refers to it', () => {
    const view = project(
      sum(shipped),
      { type: 'COUNT', alias: 'orders' },
      {
        type: 'DERIVED',
        alias: 'ratio',
        expression: {
          type: 'BINARY',
          operator: 'DIVIDE',
          left: { type: 'METRIC_REF', metric: 'total' },
          right: { type: 'METRIC_REF', metric: 'orders' },
        },
      },
    );
    const ratio = view.columns.find(entry => entry.alias === 'ratio')!;
    expect(ratio.label).toBe(
      `${metricReferenceText('SUM', 'Amount', { value: '已发运' })} ÷ ${metricReferenceText('COUNT', 'orders')}`,
    );
    expect(columnTitle(ratio, EN)).toBe(
      'Sum of Amount · 已发运 ÷ Record count',
    );
  });
});

describe('wordReferences', () => {
  it('hands each reference its condition: a value, none, or just “some”', () => {
    const seen: unknown[] = [];
    wordReferences(
      [
        metricReferenceText('SUM', 'A', { value: 'x' }),
        metricReferenceText('SUM', 'B', {}),
        metricReferenceText('SUM', 'C'),
      ].join(' '),
      (fn, label, condition) => {
        seen.push([label, condition]);
        return label;
      },
    );
    expect(seen).toEqual([
      ['A', { value: 'x' }],
      ['B', {}],
      ['C', undefined],
    ]);
  });
});

describe('metricReference over a conditioned metric', () => {
  const option: AnalysisFieldOption = {
    field: 'amount',
    label: '金额',
    groups: [],
    functions: ['SUM'],
    dateUnits: [],
    dateParts: [],
    distinctCount: false,
    percentile: false,
    any: false,
    firstLast: false,
    missingKey: false,
  };
  const naming = (...metrics: AnalysisMetric[]) => ({
    fields: [option],
    metrics,
    conditionFields: FIELDS,
    kinds: builtinFieldKinds,
  });

  // The tray names the metric from the draft by the same kernel the result
  // is projected by, so the sort and 「只保留」 say what the header says.
  it('says the same name the result column says', () => {
    const metric = sum(shipped);
    expect(metricReference(naming(metric), metric, ZH)).toBe(
      '金额的总和 · 已发运',
    );
    const several = sum(
      where({ field: 'status', operator: 'IN', value: ['SHIPPED', 'PAID'] }),
    );
    expect(metricReference(naming(several), several, ZH)).toBe(
      '金额的总和 · 有条件',
    );
    expect(metricReference(naming(several), several, EN)).toBe(
      'Sum of 金额 · conditioned',
    );
    const named = sum(shipped, '发运额');
    expect(metricReference(naming(named), named, ZH)).toBe('发运额');
  });

  // Without a runtime there are no kinds to read the condition by, and the
  // name stays what it was rather than guess.
  it('stays the plain name without kinds to read the condition', () => {
    const metric = sum(shipped);
    expect(
      metricReference({ fields: [option], metrics: [metric] }, metric, ZH),
    ).toBe('金额的总和');
  });
});
