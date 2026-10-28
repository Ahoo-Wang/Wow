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
  AggregationFunction,
  AggregationDateUnit,
  AggregationGroupType,
  AggregationMetricType,
  AggregationExpressionType,
  ComparisonOperator,
  AggregationExpressionOperator,
} from '@ahoo-wang/wow-client/dsl';
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
  type AggregationGroupTypeName,
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
    const facts = groupFacts(warehouse, [
      AggregationDateUnit.MONTH,
      AggregationDateUnit.DAY,
    ]);
    expect(groupOfType(facts, AggregationGroupType.TERMS, 'w')).toEqual({
      type: AggregationGroupType.TERMS,
      field: 'warehouse',
      alias: 'w',
      missingKey: DEFAULT_MISSING_KEY,
    });
    expect(groupOfType(facts, AggregationGroupType.HISTOGRAM, 'w')).toEqual({
      type: AggregationGroupType.HISTOGRAM,
      field: 'warehouse',
      alias: 'w',
      interval: 1,
    });
    // The first unit the capability offers, unless a unit is recommended.
    expect(
      groupOfType(facts, AggregationGroupType.DATE_HISTOGRAM, 'w'),
    ).toMatchObject({
      unit: AggregationDateUnit.MONTH,
    });
    expect(
      groupOfType(
        facts,
        AggregationGroupType.DATE_HISTOGRAM,
        'w',
        AggregationDateUnit.YEAR,
      ),
    ).toMatchObject({
      unit: AggregationDateUnit.YEAR,
    });
    expect(
      groupOfType(
        groupFacts(warehouse),
        AggregationGroupType.DATE_HISTOGRAM,
        'w',
      ),
    ).toMatchObject({ unit: AggregationDateUnit.DAY });
  });

  it('gives the sentinel bucket only to a field that can carry one', () => {
    expect(
      groupOfType(groupFacts(amount), AggregationGroupType.TERMS, 'a'),
    ).not.toHaveProperty('missingKey');
    // A registered kind answers for itself over the built-in list.
    expect(
      groupOfType(
        groupFacts(amount, [], { singleString: true }),
        AggregationGroupType.TERMS,
        'a',
      ),
    ).toHaveProperty('missingKey', DEFAULT_MISSING_KEY);
    expect(
      groupOfType(
        groupFacts(warehouse, [], builtinFieldKinds.get('string')),
        AggregationGroupType.TERMS,
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
      groups: [
        AggregationGroupType.TERMS,
        AggregationGroupType.DATE_HISTOGRAM,
      ] as AggregationGroupTypeName[],
      functions: [],
      dateUnits: [AggregationDateUnit.WEEK as const],
      distinctCount: false,
      percentile: false,
      any: false,
      missingKey: true,
    };
    for (const type of [
      AggregationGroupType.TERMS,
      AggregationGroupType.HISTOGRAM,
      AggregationGroupType.DATE_HISTOGRAM,
    ] as const)
      expect(groupOfType(option, type, 'w')).toEqual(
        groupOfType(
          groupFacts(warehouse, [AggregationDateUnit.WEEK]),
          type,
          'w',
        ),
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
      [AggregationGroupType.DATE_HISTOGRAM, AggregationGroupType.TERMS],
      [AggregationGroupType.TERMS, AggregationGroupType.DATE_HISTOGRAM],
      [AggregationGroupType.HISTOGRAM, AggregationGroupType.TERMS],
    ] as AggregationGroupTypeName[][])
      expect(
        groupFor(warehouse, { groups, dateUnits: [AggregationDateUnit.WEEK] })
          .type,
      ).toBe(groups[0]);
  });
});

describe('the summaries a field offers', () => {
  it('lists them in the order the model declares the metric types', () => {
    expect(FIELD_METRIC_TYPES).toEqual([
      AggregationMetricType.NUMERIC,
      AggregationMetricType.DISTINCT_COUNT,
      AggregationMetricType.PERCENTILE,
      AggregationMetricType.FIRST,
      AggregationMetricType.LAST,
      AggregationMetricType.ANY,
    ]);
    expect(
      summaryChoices({
        field: 'amount',
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
        distinctCount: true,
        percentile: true,
        any: true,
      }),
    ).toEqual([
      AggregationFunction.SUM,
      AggregationFunction.AVG,
      AggregationMetricType.DISTINCT_COUNT,
      AggregationMetricType.PERCENTILE,
      AggregationMetricType.ANY,
    ]);
    expect(
      summaryChoices({ field: 'amount', functions: [], percentile: true }),
    ).toEqual([AggregationMetricType.PERCENTILE]);
    // A capability as a definition declares it is read the same way.
    const capability: AggregationFieldCapability = {
      field: 'amount',
      groups: [],
      functions: [AggregationFunction.MAX],
      any: true,
    };
    expect(summaryChoices(capability)).toEqual([
      AggregationFunction.MAX,
      AggregationMetricType.ANY,
    ]);
  });

  it('builds every choice whole, and reads the choice back off it', () => {
    const choices: SummaryChoice[] = [
      AggregationFunction.SUM,
      AggregationFunction.AVG,
      AggregationMetricType.DISTINCT_COUNT,
      AggregationMetricType.PERCENTILE,
      AggregationMetricType.ANY,
    ];
    for (const choice of choices)
      expect(summaryOf(metricOfSummary({ field: 'amount' }, choice, 'm'))).toBe(
        choice,
      );
    expect(
      metricOfSummary(
        { field: 'amount' },
        AggregationMetricType.PERCENTILE,
        'm',
      ),
    ).toEqual({
      type: AggregationMetricType.PERCENTILE,
      alias: 'm',
      expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
      percentile: DEFAULT_PERCENTILE,
    });
    expect(
      metricOfSummary({ field: 'amount' }, AggregationMetricType.ANY, 'm'),
    ).toEqual({
      type: AggregationMetricType.ANY,
      alias: 'm',
      field: 'amount',
    });
    expect(
      summaryOf({ type: AggregationMetricType.COUNT, alias: 'count' }),
    ).toBeNull();
  });

  /** A fresh config's first metric comes out of the same builder. */
  it('seeds a fresh config through the same builder', () => {
    expect(
      firstMetric(false, [
        { field: 'amount', groups: [], functions: [], percentile: true },
      ]),
    ).toEqual(
      metricOfSummary(
        { field: 'amount' },
        AggregationMetricType.PERCENTILE,
        'amount_p95',
      ),
    );
  });
});

describe('groupableFields', () => {
  const fields = [
    { field: 'warehouse', groups: [AggregationGroupType.TERMS] },
    { field: 'status', groups: [AggregationGroupType.TERMS] },
    { field: 'amount', groups: [] },
  ];

  it('offers the groupable fields not already grouped by, in their order', () => {
    expect(groupableFields(fields, [])).toEqual([fields[0], fields[1]]);
    const groups: AnalysisGroup[] = [
      { type: AggregationGroupType.TERMS, field: 'warehouse', alias: 'w' },
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
    expect(HAVING_OPERATORS).toEqual([
      ComparisonOperator.GT,
      ComparisonOperator.GTE,
      ComparisonOperator.LT,
      ComparisonOperator.LTE,
      ComparisonOperator.EQ,
      ComparisonOperator.NE,
    ]);
    expect(EXPRESSION_OPERATORS).toEqual([
      AggregationExpressionOperator.ADD,
      AggregationExpressionOperator.SUBTRACT,
      AggregationExpressionOperator.MULTIPLY,
      AggregationExpressionOperator.DIVIDE,
    ]);
  });
});
