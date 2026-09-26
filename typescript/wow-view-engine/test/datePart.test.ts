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
 * Grouping by a calendar part (N2, Wow's `DATE_PART`): the weekday, the hour,
 * the day of the month, the month. What a definition may declare, what the
 * descriptor narrows, what a config is admitted with and compiles to, how a
 * key reads and how a chart lays the part's cycle out.
 */

import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationGroupType,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  ANALYSIS_DATE_PARTS,
  builtinFieldKinds,
  compileAnalysis,
  datePartsOf,
  DATE_PART_DOMAINS,
  drillGroups,
  groupFacts,
  groupOfType,
  projectAnalysis,
  shapeChart,
  validateAnalysis,
  validateDefinition,
  type AnalysisCapability,
  type AnalysisGroup,
  type DataViewDefinition,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { columnTitle, displayValue } from '../src/ui/display.js';
import { en as enMessages } from '../src/ui/messages/en.js';
import { zhCN as zhCNMessages } from '../src/ui/messages/zh-CN.js';
import {
  analysisContext as context,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const capability: AnalysisCapability = {
  count: true,
  fields: [
    {
      field: 'placedAt',
      groups: [
        AggregationGroupType.DATE_HISTOGRAM,
        AggregationGroupType.DATE_PART,
      ],
      functions: [],
      dateUnits: [AggregationDateUnit.DAY],
    },
    {
      field: 'shippedAt',
      groups: [AggregationGroupType.DATE_PART],
      functions: [],
      dateParts: [AggregationDatePart.HOUR_OF_DAY],
    },
  ],
  dense: true,
};

function definition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'placedAt', label: 'Placed', kind: 'datetime' },
      { name: 'shippedAt', label: 'Shipped', kind: 'datetime' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
    ],
    analysis: capability,
    ...overrides,
  };
}

const weekday: AnalysisGroup = {
  type: 'DATE_PART',
  field: 'placedAt',
  alias: 'weekday',
  part: 'DAY_OF_WEEK',
};
const hour: AnalysisGroup = {
  type: 'DATE_PART',
  field: 'placedAt',
  alias: 'hour',
  part: 'HOUR_OF_DAY',
};

const check = (groups: AnalysisGroup[]) =>
  codes(
    validateAnalysis(
      definition(),
      config({
        groups,
        sort: [],
        chart:
          groups.length === 2
            ? {
                type: 'heatmap',
                heatmap: {
                  x: groups[0]!.alias,
                  y: groups[1]!.alias,
                  value: 'orders',
                },
              }
            : {
                type: 'bar',
                cartesian: {
                  x: groups[0]!.alias,
                  series: [{ metric: 'orders' }],
                },
              },
      }),
      builtinFieldKinds,
    ),
  );

describe('the parts a field offers', () => {
  it('lists every part in the offered order when the field names none', () => {
    expect(datePartsOf(capability.fields[0])).toEqual([
      'DAY_OF_WEEK',
      'HOUR_OF_DAY',
      'DAY_OF_MONTH',
      'MONTH_OF_YEAR',
    ]);
    expect(ANALYSIS_DATE_PARTS).toEqual(
      expect.arrayContaining(Object.values(AggregationDatePart)),
    );
    expect(ANALYSIS_DATE_PARTS).toHaveLength(
      Object.values(AggregationDatePart).length,
    );
  });

  it('keeps to the declared parts, and offers none without DATE_PART', () => {
    expect(datePartsOf(capability.fields[1])).toEqual(['HOUR_OF_DAY']);
    expect(
      datePartsOf({ groups: [AggregationGroupType.DATE_HISTOGRAM] }),
    ).toEqual([]);
    expect(datePartsOf(undefined)).toEqual([]);
  });

  it('starts a part dimension at the first part offered', () => {
    const [placed, shipped] = definition().fields;
    expect(
      groupOfType(
        groupFacts(placed!, [], undefined, capability.fields[0]),
        'DATE_PART',
        'p',
      ),
    ).toEqual({
      type: 'DATE_PART',
      field: 'placedAt',
      alias: 'p',
      part: 'DAY_OF_WEEK',
    });
    expect(
      groupOfType(
        groupFacts(shipped!, [], undefined, capability.fields[1]),
        'DATE_PART',
        's',
      ),
    ).toMatchObject({ part: 'HOUR_OF_DAY' });
  });
});

describe('definition admission', () => {
  it('refuses a part on a field that holds no time, and an unknown part', () => {
    const issues = validateDefinition(
      definition({
        analysis: {
          count: true,
          fields: [
            {
              field: 'warehouse',
              groups: [AggregationGroupType.DATE_PART],
              functions: [],
            },
            {
              field: 'placedAt',
              groups: [AggregationGroupType.DATE_PART],
              functions: [],
              dateParts: ['WEEK_OF_YEAR' as AggregationDatePart],
            },
          ],
        },
      }),
      builtinFieldKinds,
    );
    expect(issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'definition.analysis.date-part-not-temporal',
          path: ['analysis', 'fields', 0, 'groups'],
        }),
        expect.objectContaining({
          code: 'definition.analysis.date-part-unknown',
          path: ['analysis', 'fields', 1, 'dateParts', 0],
          params: { field: 'placedAt', value: 'WEEK_OF_YEAR' },
        }),
      ]),
    );
  });

  it('admits a time field that offers its parts', () => {
    expect(
      validateDefinition(definition(), builtinFieldKinds).filter(
        found => found.severity === 'error',
      ),
    ).toEqual([]);
  });
});

describe('narrowing by the descriptor', () => {
  function described(parts: AggregationDatePart[]): QueryModelDescriptor {
    const base = ordersDescriptor();
    return {
      ...base,
      fields: [
        describedField('placedAt'),
        describedField('shippedAt'),
        describedField('warehouse'),
      ],
      analysis: {
        ...base.analysis,
        dateUnits: [AggregationDateUnit.DAY],
        dateParts: parts,
      },
    };
  }

  it('keeps the parts the model groups by and says which it dropped', () => {
    const { definition: narrowed, findings } = narrowDefinition(
      definition(),
      described([AggregationDatePart.DAY_OF_WEEK]),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.fields[0]).toMatchObject({
      groups: ['DATE_HISTOGRAM', 'DATE_PART'],
      dateParts: ['DAY_OF_WEEK'],
    });
    expect(findings).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'capability.analysis.field-narrowed',
          params: {
            field: 'placedAt',
            dropped: 'HOUR_OF_DAY, DAY_OF_MONTH, MONTH_OF_YEAR',
          },
        }),
      ]),
    );
    // The shipped hour is not among them: its field groups no other way,
    // so it no longer groups at all.
    expect(narrowed.analysis?.fields.map(entry => entry.field)).not.toContain(
      'shippedAt',
    );
  });

  it('leaves every part where the model groups by all of them', () => {
    const { definition: narrowed } = narrowDefinition(
      definition(),
      described(Object.values(AggregationDatePart)),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.fields[0]).not.toHaveProperty('dateParts');
    expect(narrowed.analysis?.fields[1]).toMatchObject({
      dateParts: ['HOUR_OF_DAY'],
    });
  });

  it('takes DATE_PART away where the model reads no part', () => {
    const { definition: narrowed } = narrowDefinition(
      definition(),
      described([]),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.fields[0]?.groups).toEqual(['DATE_HISTOGRAM']);
  });
});

describe('config admission', () => {
  it('admits a weekday by an hour', () => {
    expect(check([weekday, hour])).toEqual([]);
  });

  it('refuses a part the field does not offer', () => {
    expect(
      check([
        {
          type: 'DATE_PART',
          field: 'shippedAt',
          alias: 'd',
          part: 'DAY_OF_WEEK',
        },
      ]),
    ).toEqual(['analysis.group.part-unsupported']);
  });

  it('fills the whole cycle only as the one dimension, and never with a blank zone', () => {
    expect(check([{ ...weekday, dense: true }, hour])).toEqual([
      'analysis.group.dense-not-alone',
    ]);
    expect(check([{ ...weekday, dense: true }])).toEqual([]);
    expect(check([{ ...weekday, timeZone: ' ' }])).toEqual([
      'analysis.group.blank-time-zone',
    ]);
  });
});

describe('compileAnalysis', () => {
  it('reads the part in the engine zone unless the group names one', () => {
    const query = compileAnalysis(
      definition(),
      config({
        groups: [weekday, { ...hour, timeZone: 'Asia/Shanghai', dense: false }],
        sort: [],
      }),
      builtinFieldKinds,
      { ...context, timeZone: 'Europe/Berlin' },
    );
    expect(query.groupBy).toEqual([
      {
        type: 'DATE_PART',
        field: 'placedAt',
        alias: 'weekday',
        part: 'DAY_OF_WEEK',
        timeZone: 'Europe/Berlin',
      },
      {
        type: 'DATE_PART',
        field: 'placedAt',
        alias: 'hour',
        part: 'HOUR_OF_DAY',
        timeZone: 'Asia/Shanghai',
        dense: false,
      },
    ]);
  });
});

describe('a part key on screen', () => {
  it('names the weekday, the hour, the day and the month in the language', () => {
    const zh = { locale: 'zh-CN' };
    const en = { locale: 'en-US' };
    const read = (
      value: unknown,
      part: AnalysisGroup & { type: 'DATE_PART' },
    ) => [
      displayValue(value, { datePart: part.part }, zh),
      displayValue(value, { datePart: part.part }, en),
    ];
    expect(read(1, weekday as never)).toEqual(['周一', 'Mon']);
    expect(read(7, weekday as never)).toEqual(['周日', 'Sun']);
    expect(read('20', hour as never)).toEqual(['20时', '20:00']);
    expect(displayValue(0, { datePart: 'HOUR_OF_DAY' }, zh)).toBe('0时');
    expect(displayValue(9, { datePart: 'MONTH_OF_YEAR' }, zh)).toBe('9月');
    expect(displayValue(3, { datePart: 'DAY_OF_MONTH' }, zh)).toBe('3日');
  });

  it('leaves a key outside the cycle, and a missing one, to the caller', () => {
    expect(
      displayValue(8, { datePart: 'DAY_OF_WEEK' }, { locale: 'zh-CN' }),
    ).toBeUndefined();
    expect(
      displayValue('x', { datePart: 'DAY_OF_WEEK' }, { locale: 'zh-CN' }),
    ).toBeUndefined();
    expect(
      displayValue(null, { datePart: 'DAY_OF_WEEK' }, { locale: 'zh-CN' }),
    ).toBeUndefined();
  });

  it('heads the column with the field and its cycle', () => {
    const messages = (catalogue: Record<string, string>) => ({
      label: (key: string, params?: Record<string, unknown>) =>
        catalogue[key]!.replace(/\{(\w+)\}/g, (_, name: string) =>
          String(params?.[name]),
        ),
    });
    expect(
      columnTitle(
        { label: '下单时间', datePart: 'DAY_OF_WEEK' },
        messages(zhCNMessages as never) as never,
      ),
    ).toBe('下单时间（星期）');
    expect(
      columnTitle(
        { label: 'Placed', datePart: 'HOUR_OF_DAY' },
        messages(enMessages as never) as never,
      ),
    ).toBe('Placed (hour of day)');
  });

  it('is projected as its part, not as the time field it was read from', () => {
    const view = projectAnalysis(
      definition(),
      config({ groups: [weekday], sort: [] }),
      [{ weekday: 1, orders: 3 }],
    );
    const column = view.columns.find(entry => entry.alias === 'weekday');
    expect(column).toMatchObject({ datePart: 'DAY_OF_WEEK', role: 'group' });
    expect(column).not.toHaveProperty('cell');
    expect(column).not.toHaveProperty('dateUnit');
  });
});

describe('a chart along a cycle', () => {
  it('draws 星期 × 时段 as the whole week by the whole day', () => {
    const data = shapeChart(
      config({
        groups: [hour, weekday],
        sort: [],
        chart: {
          type: 'heatmap',
          heatmap: { x: 'hour', y: 'weekday', value: 'orders' },
        },
      }),
      [
        { hour: 20, weekday: 3, orders: 5 },
        { hour: 9, weekday: 1, orders: 2 },
      ],
    );
    expect(data).toMatchObject({ type: 'heatmap' });
    if (data?.type !== 'heatmap') return;
    expect(data.ys).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(data.xs).toEqual(Array.from({ length: 24 }, (_, index) => index));
    expect(data.cells[2]![20]).toBe(5);
    expect(data.cells[0]![9]).toBe(2);
    expect(data.cells[1]![9]).toBeNull();
  });

  it('puts every weekday on a bar axis, the quiet ones known empty at 0', () => {
    const data = shapeChart(
      config({
        groups: [weekday],
        sort: [{ alias: 'orders', direction: 'DESC' }],
        chart: {
          type: 'bar',
          cartesian: { x: 'weekday', series: [{ metric: 'orders' }] },
        },
      }),
      [
        { weekday: 5, orders: 9 },
        { weekday: 2, orders: 4 },
      ],
    );
    if (data?.type !== 'cartesian') throw new Error('not cartesian');
    expect(data.points.map(point => point.x)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    expect(data.points[0]).toMatchObject({
      values: { orders: 0 },
      filled: ['orders'],
    });
    expect(data.points[4]).toMatchObject({ values: { orders: 9 } });
  });

  it('orders a weekday legend Monday first, and keeps a stray key last', () => {
    const data = shapeChart(
      config({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }, weekday],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: {
            x: 'wh',
            splitBy: 'weekday',
            series: [{ metric: 'orders' }],
          },
        },
      }),
      [
        { wh: 'A', weekday: 6, orders: 1 },
        { wh: 'A', weekday: null, orders: 1 },
        { wh: 'A', weekday: 2, orders: 1 },
      ],
    );
    if (data?.type !== 'cartesian') throw new Error('not cartesian');
    expect(data.series.map(entry => entry.value)).toEqual([2, 6, null]);
  });

  it('knows the domain of every part', () => {
    expect(DATE_PART_DOMAINS).toEqual({
      DAY_OF_WEEK: [1, 7],
      HOUR_OF_DAY: [0, 23],
      DAY_OF_MONTH: [1, 31],
      MONTH_OF_YEAR: [1, 12],
    });
  });
});

describe('following a part back to records', () => {
  it('cannot: no stored condition says “every Monday”', () => {
    expect(
      drillGroups(
        config({ groups: [weekday] }),
        definition().fields,
        builtinFieldKinds,
        { weekday: 1, orders: 3 },
        context,
      ),
    ).toBeNull();
  });
});
