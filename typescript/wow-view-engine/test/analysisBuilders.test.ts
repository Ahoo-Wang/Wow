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

import { AggregationFunction } from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MISSING_KEY,
  DEFAULT_PERCENTILE,
  EXPRESSION_OPERATORS,
  HAVING_OPERATORS,
  firstMetric,
  groupFacts,
  groupOfType,
  groupableFields,
  metricOfSummary,
  summaryChoices,
  summaryOf,
  type SummaryChoice,
  groupFor,
} from '../src/analysis/index.js';
import { builtinFieldKinds } from '../src/filter/index.js';
import {
  FIELD_METRIC_TYPES,
  type AggregationFieldCapability,
  type AnalysisGroup,
  type AnalysisGroupType,
  type FieldDefinition,
} from '../src/model/index.js';

/**
 * How a field becomes a dimension or a metric is one builder of each in the
 * analysis kernel: a fresh config, a split from the follow-up menu, a field
 * picked on the tray and a card's type or summary switched all go through
 * it, whichever shape they hold the field in.
 */

const warehouse: FieldDefinition = {
  name: 'warehouse',
  label: 'Warehouse',
  kind: 'string',
};
const amount: FieldDefinition = {
  name: 'amount',
  label: 'Amount',
  kind: 'number',
};

describe('groupOfType', () => {
  it('builds each type whole, with the default it starts with', () => {
    const facts = groupFacts(warehouse, ['MONTH', 'DAY']);
    expect(groupOfType(facts, 'TERMS', 'w')).toEqual({
      type: 'TERMS',
      field: 'warehouse',
      alias: 'w',
      missingKey: DEFAULT_MISSING_KEY,
    });
    expect(groupOfType(facts, 'HISTOGRAM', 'w')).toEqual({
      type: 'HISTOGRAM',
      field: 'warehouse',
      alias: 'w',
      interval: 1,
    });
    // The first unit the capability offers, unless a unit is recommended.
    expect(groupOfType(facts, 'DATE_HISTOGRAM', 'w')).toMatchObject({
      unit: 'MONTH',
    });
    expect(groupOfType(facts, 'DATE_HISTOGRAM', 'w', 'YEAR')).toMatchObject({
      unit: 'YEAR',
    });
    expect(
      groupOfType(groupFacts(warehouse), 'DATE_HISTOGRAM', 'w'),
    ).toMatchObject({ unit: 'DAY' });
  });

  it('gives the sentinel bucket only to a field that can carry one', () => {
    expect(groupOfType(groupFacts(amount), 'TERMS', 'a')).not.toHaveProperty(
      'missingKey',
    );
    // A registered kind answers for itself over the built-in list.
    expect(
      groupOfType(groupFacts(amount, [], { singleString: true }), 'TERMS', 'a'),
    ).toHaveProperty('missingKey', DEFAULT_MISSING_KEY);
    expect(
      groupOfType(
        groupFacts(warehouse, [], builtinFieldKinds.get('string')),
        'TERMS',
        'w',
      ),
    ).toHaveProperty('missingKey', DEFAULT_MISSING_KEY);
  });

  /**
   * The editor's option of a field and the definition's own field read with
   * its capability are one input: the same field builds the same dimension
   * whichever of the two a caller holds.
   */
  it('builds the same dimension from the editor’s option and from the definition', () => {
    const option = {
      field: 'warehouse',
      label: 'Warehouse',
      groups: ['TERMS', 'DATE_HISTOGRAM'] as AnalysisGroupType[],
      functions: [],
      dateUnits: ['WEEK' as const],
      distinctCount: false,
      percentile: false,
      any: false,
      missingKey: true,
    };
    for (const type of ['TERMS', 'HISTOGRAM', 'DATE_HISTOGRAM'] as const)
      expect(groupOfType(option, type, 'w')).toEqual(
        groupOfType(groupFacts(warehouse, ['WEEK']), type, 'w'),
      );
  });

  /**
   * The order a definition lists a field's group types in is its author's
   * word on how the field is first looked at, so the tray's 「添加维度」 and
   * the follow-up's 「按其他维度细分…」 both take the first one. They once
   * differed: the split preferred a value grouping wherever one was offered,
   * so a field declared date-first was grouped by date in the tray and by
   * value from the menu.
   */
  it('takes the type a definition lists first, in the tray and in a split alike', () => {
    for (const groups of [
      ['DATE_HISTOGRAM', 'TERMS'],
      ['TERMS', 'DATE_HISTOGRAM'],
      ['HISTOGRAM', 'TERMS'],
    ] as AnalysisGroupType[][])
      expect(groupFor(warehouse, { groups, dateUnits: ['WEEK'] }).type).toBe(
        groups[0],
      );
  });
});

describe('the summaries a field offers', () => {
  it('lists them in the order the model declares the metric types', () => {
    expect(FIELD_METRIC_TYPES).toEqual([
      'NUMERIC',
      'DISTINCT_COUNT',
      'PERCENTILE',
      'ANY',
    ]);
    expect(
      summaryChoices({
        field: 'amount',
        functions: ['SUM', 'AVG'],
        distinctCount: true,
        percentile: true,
        any: true,
      }),
    ).toEqual(['SUM', 'AVG', 'DISTINCT_COUNT', 'PERCENTILE', 'ANY']);
    expect(
      summaryChoices({ field: 'amount', functions: [], percentile: true }),
    ).toEqual(['PERCENTILE']);
    // A capability as a definition declares it is read the same way.
    const capability: AggregationFieldCapability = {
      field: 'amount',
      groups: [],
      functions: [AggregationFunction.MAX],
      any: true,
    };
    expect(summaryChoices(capability)).toEqual(['MAX', 'ANY']);
  });

  it('builds every choice whole, and reads the choice back off it', () => {
    const choices: SummaryChoice[] = [
      'SUM',
      'AVG',
      'DISTINCT_COUNT',
      'PERCENTILE',
      'ANY',
    ];
    for (const choice of choices)
      expect(summaryOf(metricOfSummary({ field: 'amount' }, choice, 'm'))).toBe(
        choice,
      );
    expect(metricOfSummary({ field: 'amount' }, 'PERCENTILE', 'm')).toEqual({
      type: 'PERCENTILE',
      alias: 'm',
      expression: { type: 'FIELD', field: 'amount' },
      percentile: DEFAULT_PERCENTILE,
    });
    expect(metricOfSummary({ field: 'amount' }, 'ANY', 'm')).toEqual({
      type: 'ANY',
      alias: 'm',
      field: 'amount',
    });
    expect(summaryOf({ type: 'COUNT', alias: 'count' })).toBeNull();
  });

  /** A fresh config's first metric comes out of the same builder. */
  it('seeds a fresh config through the same builder', () => {
    expect(
      firstMetric(false, [
        { field: 'amount', groups: [], functions: [], percentile: true },
      ]),
    ).toEqual(metricOfSummary({ field: 'amount' }, 'PERCENTILE', 'amount_p95'));
  });
});

describe('groupableFields', () => {
  const fields = [
    { field: 'warehouse', groups: ['TERMS'] },
    { field: 'status', groups: ['TERMS'] },
    { field: 'amount', groups: [] },
  ];

  it('offers the groupable fields not already grouped by, in their order', () => {
    expect(groupableFields(fields, [])).toEqual([fields[0], fields[1]]);
    const groups: AnalysisGroup[] = [
      { type: 'TERMS', field: 'warehouse', alias: 'w' },
    ];
    expect(groupableFields(fields, groups)).toEqual([fields[1]]);
  });
});

describe('the operator lists', () => {
  /**
   * Both are `Record`s over the model's operator type, so an operator Wow
   * adds does not compile until it is placed; this pins the order a select
   * shows them in.
   */
  it('lists every operator the model has, in the order a select shows them', () => {
    expect(HAVING_OPERATORS).toEqual(['GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE']);
    expect(EXPRESSION_OPERATORS).toEqual([
      'ADD',
      'SUBTRACT',
      'MULTIPLY',
      'DIVIDE',
    ]);
  });
});
