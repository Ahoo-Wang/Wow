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
 * A time between two moments (N3, Wow's `DATE_DIFF`) as a metric and as a
 * band dimension: admission, the descriptor, compilation, how it reads and
 * what it is called.
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  DateDiffUnit,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  dateDiffUnitsOf,
  drillGroups,
  durationMetric,
  expressionFieldsOf,
  expressionText,
  groupFieldsOf,
  isDuration,
  projectAnalysis,
  validateAnalysis,
  withElements,
  type AnalysisCapability,
  type AnalysisExpression,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import {
  analysisContext as context,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const capability: AnalysisCapability = {
  count: true,
  expressions: true,
  fields: [
    {
      field: 'paidAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM],
      functions: [],
      dateUnits: [AggregationDateUnit.DAY],
    },
    {
      field: 'shippedAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM],
      functions: [],
      dateUnits: [AggregationDateUnit.DAY],
    },
    {
      field: 'warehouse',
      groups: [AggregationGroupType.TERMS],
      functions: [],
    },
    {
      field: 'amount',
      groups: [],
      functions: [AggregationFunction.SUM],
    },
  ],
};

function definition(
  overrides: Partial<AnalysisCapability> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'paidAt', label: 'Paid', kind: 'datetime' },
      { name: 'shippedAt', label: 'Shipped', kind: 'datetime' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
    ],
    analysis: { ...capability, ...overrides },
  };
}

const HOURS: AnalysisExpression = {
  type: 'DATE_DIFF',
  from: 'paidAt',
  to: 'shippedAt',
  unit: 'HOUR',
};
const average: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'hours',
  function: 'AVG',
  expression: HOURS,
};
const bands: AnalysisGroup = {
  type: 'HISTOGRAM',
  alias: 'band',
  expression: HOURS,
  interval: 4,
};

const durationConfig = (
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig =>
  config({
    groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
    metrics: [average],
    sort: [],
    chart: {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'hours' }] },
    },
    ...overrides,
  });

const check = (
  overrides: Partial<AnalysisViewConfig>,
  on: DataViewDefinition = definition(),
) => codes(validateAnalysis(on, durationConfig(overrides), builtinFieldKinds));

describe('the units a duration is measured in', () => {
  it('are every one where computed expressions are on, and none where they are off', () => {
    expect(dateDiffUnitsOf(capability)).toEqual([
      'HOUR',
      'DAY',
      'MINUTE',
      'SECOND',
    ]);
    expect(dateDiffUnitsOf({ ...capability, dateDiffUnits: ['DAY'] })).toEqual([
      'DAY',
    ]);
    expect(dateDiffUnitsOf({ ...capability, expressions: false })).toEqual([]);
    expect(dateDiffUnitsOf(undefined)).toEqual([]);
  });
});

describe('a duration as a metric', () => {
  it('is built from two times and a unit, and read back as a duration', () => {
    const built = durationMetric('paidAt', 'shippedAt', 'HOUR', ['duration']);
    expect(built).toEqual({
      type: 'NUMERIC',
      alias: 'duration_1',
      function: 'AVG',
      expression: HOURS,
    });
    expect(isDuration(built)).toBe(true);
    expect(
      isDuration({ ...built, type: 'PERCENTILE', percentile: 90 } as never),
    ).toBe(true);
    expect(isDuration({ type: 'COUNT', alias: 'n' })).toBe(false);
    expect(expressionFieldsOf(HOURS)).toEqual(['paidAt', 'shippedAt']);
    expect(expressionText(HOURS, name => name.toUpperCase())).toBe(
      'PAIDAT → SHIPPEDAT',
    );
  });

  it('is admitted where the capability computes expressions in its unit', () => {
    expect(check({})).toEqual([]);
    expect(check({}, definition({ expressions: false }))).toEqual([
      'analysis.expressions.undeclared',
    ]);
    expect(check({}, definition({ dateDiffUnits: ['DAY'] }))).toEqual([
      'analysis.date-diff.unit-unsupported',
    ]);
  });

  it('runs between two times the unit holds and takes into arithmetic', () => {
    const between = (from: string, to: string) =>
      check({
        metrics: [
          {
            ...average,
            expression: { type: 'DATE_DIFF', from, to, unit: 'HOUR' },
          },
        ],
      });
    expect(between('warehouse', 'shippedAt')).toEqual([
      'analysis.date-diff.not-time',
    ]);
    expect(between('paidAt', 'gone')).toEqual(['analysis.field.unknown']);
    expect(
      check(
        {},
        definition({
          fields: capability.fields.map(field =>
            field.field === 'paidAt'
              ? { ...field, expressionInput: false }
              : field,
          ),
        }),
      ),
    ).toEqual(['analysis.expression.operand-unsupported']);
    expect(
      check({
        metrics: [
          {
            ...average,
            expression: { type: 'DATE_DIFF', from: 1, to: 'paidAt' } as never,
          },
        ],
      }),
    ).toContain('analysis.expression.malformed');
  });

  it('compiles to a DATE_DIFF, and reads in its unit under its two times', () => {
    const query = compileAnalysis(
      definition(),
      durationConfig({
        metrics: [
          average,
          {
            type: 'PERCENTILE',
            alias: 'p90',
            percentile: 90,
            expression: HOURS,
          },
        ],
      }),
      builtinFieldKinds,
      context,
    );
    expect(query.metrics[0]).toMatchObject({
      function: 'AVG',
      expression: {
        type: 'DATE_DIFF',
        from: 'paidAt',
        to: 'shippedAt',
        unit: DateDiffUnit.HOUR,
      },
    });
    const view = projectAnalysis(
      definition(),
      durationConfig({
        metrics: [
          average,
          {
            type: 'PERCENTILE',
            alias: 'p90',
            percentile: 90,
            expression: HOURS,
          },
        ],
      }),
      [{ wh: 'A', hours: 12.5, p90: 30 }],
    );
    const hours = view.columns.find(column => column.alias === 'hours');
    expect(hours).toMatchObject({
      label: '(Paid → Shipped)',
      numberFormat: { style: 'unit', unit: 'hour' },
    });
    expect(
      view.columns.find(column => column.alias === 'p90')?.numberFormat,
    ).toMatchObject({ style: 'unit', unit: 'hour' });
  });
});

describe('a duration as a band dimension', () => {
  const banded = (overrides: Partial<AnalysisViewConfig> = {}) =>
    durationConfig({
      groups: [bands],
      metrics: [{ type: 'COUNT', alias: 'orders' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'band', series: [{ metric: 'orders' }] },
      },
      ...overrides,
    });

  it('names the fields it reads, compiles its expression and its width', () => {
    expect(groupFieldsOf(bands)).toEqual(['paidAt', 'shippedAt']);
    expect(
      groupFieldsOf({ type: 'TERMS', field: 'warehouse', alias: 'w' }),
    ).toEqual(['warehouse']);
    expect(
      compileAnalysis(definition(), banded(), builtinFieldKinds, context)
        .groupBy,
    ).toEqual([
      {
        type: AggregationGroupType.HISTOGRAM,
        expression: {
          type: 'DATE_DIFF',
          from: 'paidAt',
          to: 'shippedAt',
          unit: 'HOUR',
        },
        alias: 'band',
        interval: 4,
      },
    ]);
  });

  it('is admitted as a formula is, and a band as a band is', () => {
    expect(
      codes(validateAnalysis(definition(), banded(), builtinFieldKinds)),
    ).toEqual([]);
    expect(
      codes(
        validateAnalysis(
          definition({ expressions: false }),
          banded(),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.expressions.undeclared']);
    expect(
      codes(
        validateAnalysis(
          definition(),
          banded({ groups: [{ ...bands, interval: 0 }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.group.interval-not-positive']);
    expect(
      codes(
        validateAnalysis(
          definition(),
          banded({
            groups: [
              { type: 'HISTOGRAM', alias: 'band', interval: 4 } as never,
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toContain('analysis.config.malformed');
  });

  it('reads as what it computes, in its unit, and leads to no records', () => {
    const view = projectAnalysis(definition(), banded(), [
      { band: 0, orders: 3 },
      { band: 4, orders: 1 },
    ]);
    expect(view.columns[0]).toMatchObject({
      label: 'Paid → Shipped',
      interval: 4,
      numberFormat: { style: 'unit', unit: 'hour' },
    });
    expect(
      drillGroups(
        banded(),
        definition().fields,
        builtinFieldKinds,
        { band: 0, orders: 3 },
        context,
      ),
    ).toBeNull();
  });

  it('stays through an expansion only while its times are in the counting unit', () => {
    const kept = withElements(banded(), [], definition(), capability);
    expect(kept.groups).toEqual([bands]);
  });
});

describe('narrowing by the descriptor', () => {
  function described(units: DateDiffUnit[], expressions = true) {
    const base = ordersDescriptor();
    const descriptor: QueryModelDescriptor = {
      ...base,
      fields: [
        describedField('paidAt'),
        describedField('shippedAt'),
        describedField('warehouse'),
        describedField('amount'),
      ],
      analysis: {
        ...base.analysis,
        expressions,
        dateUnits: [AggregationDateUnit.DAY],
        dateDiffUnits: units,
      },
    };
    return descriptor;
  }

  it('keeps the units the entry measures in', () => {
    const { definition: narrowed } = narrowDefinition(
      definition(),
      described([DateDiffUnit.HOUR, DateDiffUnit.DAY]),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.dateDiffUnits).toEqual(['HOUR', 'DAY']);
  });

  it('takes durations away where the entry turns computed expressions off', () => {
    const { definition: narrowed } = narrowDefinition(
      definition(),
      described([], false),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.expressions).toBe(false);
    expect(dateDiffUnitsOf(narrowed.analysis)).toEqual([]);
  });
});
