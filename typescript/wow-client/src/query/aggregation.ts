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
  filter,
  type ElementFilterExpression,
  type FilterExpression,
  type LogicalField,
} from './filter';
import type { FieldSort } from './sort';

export enum AggregationGroupType {
  TERMS = 'TERMS',
  HISTOGRAM = 'HISTOGRAM',
  DATE_HISTOGRAM = 'DATE_HISTOGRAM',
}

export enum AggregationMetricType {
  COUNT = 'COUNT',
  NUMERIC = 'NUMERIC',
}

export enum AggregationExpressionType {
  FIELD = 'FIELD',
  CONSTANT = 'CONSTANT',
  BINARY = 'BINARY',
}

export enum AggregationExpressionOperator {
  ADD = 'ADD',
  SUBTRACT = 'SUBTRACT',
  MULTIPLY = 'MULTIPLY',
  DIVIDE = 'DIVIDE',
}

export enum AggregationDateUnit {
  YEAR = 'YEAR',
  QUARTER = 'QUARTER',
  MONTH = 'MONTH',
  WEEK = 'WEEK',
  DAY = 'DAY',
  HOUR = 'HOUR',
  MINUTE = 'MINUTE',
  SECOND = 'SECOND',
}

export enum AggregationFunction {
  SUM = 'SUM',
  AVG = 'AVG',
  MIN = 'MIN',
  MAX = 'MAX',
}

export interface AggregationElement {
  path: LogicalField;
  filter?: ElementFilterExpression;
}

interface AggregationGroupBase<FIELDS extends string = string> {
  field: LogicalField<FIELDS>;
  alias: string;
}

export interface TermsAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.TERMS;
}

export interface HistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}

export interface DateHistogramAggregationGroup<
  FIELDS extends string = string,
> extends AggregationGroupBase<FIELDS> {
  type: AggregationGroupType.DATE_HISTOGRAM;
  unit: AggregationDateUnit;
  timeZone?: string;
}

export type AggregationGroup<FIELDS extends string = string> =
  | TermsAggregationGroup<FIELDS>
  | HistogramAggregationGroup<FIELDS>
  | DateHistogramAggregationGroup<FIELDS>;

export interface FieldAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.FIELD;
  field: LogicalField<FIELDS>;
}

export interface ConstantAggregationExpression {
  type: AggregationExpressionType.CONSTANT;
  value: number;
}

export interface BinaryAggregationExpression<FIELDS extends string = string> {
  type: AggregationExpressionType.BINARY;
  operator: AggregationExpressionOperator;
  left: AggregationExpression<FIELDS>;
  right: AggregationExpression<FIELDS>;
}

export type AggregationExpression<FIELDS extends string = string> =
  | FieldAggregationExpression<FIELDS>
  | ConstantAggregationExpression
  | BinaryAggregationExpression<FIELDS>;

export interface CountAggregationMetric {
  type: AggregationMetricType.COUNT;
  alias: string;
}

export interface NumericAggregationMetric<FIELDS extends string = string> {
  type: AggregationMetricType.NUMERIC;
  function: AggregationFunction;
  expression: AggregationExpression<FIELDS>;
  alias: string;
}

export type AggregationMetric<FIELDS extends string = string> =
  CountAggregationMetric | NumericAggregationMetric<FIELDS>;

export interface AggregationQuery<
  ROOT_FIELDS extends string = string,
  AGGREGATION_FIELDS extends string = ROOT_FIELDS,
> {
  filter?: FilterExpression<ROOT_FIELDS>;
  elements?: AggregationElement[];
  groupBy?: AggregationGroup<AGGREGATION_FIELDS>[];
  metrics: [
    AggregationMetric<AGGREGATION_FIELDS>,
    ...AggregationMetric<AGGREGATION_FIELDS>[],
  ];
  sort?: FieldSort[];
  limit?: number;
}

export interface HistogramAggregationOptions {
  interval: number;
  alias: string;
}

export interface DateHistogramAggregationOptions {
  unit: AggregationDateUnit;
  alias: string;
  timeZone?: string;
}

function aggregationField<FIELDS extends string>(field: FIELDS): FIELDS {
  return filter.exists(field).field;
}

function aggregationAlias(alias: string): string {
  aggregationField(alias);
  if (alias.includes('.')) {
    throw new TypeError('aggregation alias must contain one segment.');
  }
  if (alias.startsWith('__wow')) {
    throw new TypeError(
      'aggregation alias must not use the reserved __wow prefix.',
    );
  }
  return alias;
}

function binary<FIELDS extends string>(
  operator: AggregationExpressionOperator,
  left: AggregationExpression<FIELDS>,
  right: AggregationExpression<FIELDS>,
): BinaryAggregationExpression<FIELDS> {
  return { type: AggregationExpressionType.BINARY, operator, left, right };
}

function numeric<FIELDS extends string>(
  fn: AggregationFunction,
  expression: AggregationExpression<FIELDS>,
  alias: string,
): NumericAggregationMetric<FIELDS> {
  return {
    type: AggregationMetricType.NUMERIC,
    function: fn,
    expression,
    alias: aggregationAlias(alias),
  };
}

export const aggregation = {
  element(
    path: string,
    predicate?: ElementFilterExpression,
  ): AggregationElement {
    const validPath = aggregationField(path);
    if (predicate === undefined) return { path: validPath };
    filter.elementMatch(path, predicate);
    return { path: validPath, filter: predicate };
  },
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS> {
    return {
      type: AggregationExpressionType.FIELD,
      field: aggregationField(field),
    };
  },
  constant(value: number): ConstantAggregationExpression {
    if (!Number.isFinite(value)) {
      throw new TypeError('aggregation constant must be finite.');
    }
    return { type: AggregationExpressionType.CONSTANT, value };
  },
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.ADD, left, right),
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.SUBTRACT, left, right),
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.MULTIPLY, left, right),
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.DIVIDE, left, right),
  terms<FIELDS extends string>(
    field: FIELDS,
    alias: string,
  ): TermsAggregationGroup<FIELDS> {
    return {
      type: AggregationGroupType.TERMS,
      field: aggregationField(field),
      alias: aggregationAlias(alias),
    };
  },
  histogram<FIELDS extends string>(
    field: FIELDS,
    { interval, alias }: HistogramAggregationOptions,
  ): HistogramAggregationGroup<FIELDS> {
    if (!Number.isFinite(interval) || interval <= 0) {
      throw new TypeError(
        'histogram interval must be finite and greater than 0.',
      );
    }
    return {
      type: AggregationGroupType.HISTOGRAM,
      field: aggregationField(field),
      interval,
      alias: aggregationAlias(alias),
    };
  },
  dateHistogram<FIELDS extends string>(
    field: FIELDS,
    { unit, alias, timeZone = 'UTC' }: DateHistogramAggregationOptions,
  ): DateHistogramAggregationGroup<FIELDS> {
    if (!Object.values(AggregationDateUnit).includes(unit)) {
      throw new TypeError('date histogram unit is invalid.');
    }
    if (typeof timeZone !== 'string' || !timeZone.trim()) {
      throw new TypeError('date histogram timeZone cannot be blank.');
    }
    return {
      type: AggregationGroupType.DATE_HISTOGRAM,
      field: aggregationField(field),
      unit,
      alias: aggregationAlias(alias),
      timeZone,
    };
  },
  count(alias: string): CountAggregationMetric {
    return {
      type: AggregationMetricType.COUNT,
      alias: aggregationAlias(alias),
    };
  },
  sum: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.SUM, expression, alias),
  avg: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.AVG, expression, alias),
  min: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.MIN, expression, alias),
  max: <FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    alias: string,
  ) => numeric(AggregationFunction.MAX, expression, alias),
};
