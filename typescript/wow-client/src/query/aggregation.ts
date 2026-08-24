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

import type {
  ElementFilterExpression,
  FilterExpression,
  LogicalField,
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

interface AggregationGroupBase {
  field: LogicalField;
  alias: string;
}

export interface TermsAggregationGroup extends AggregationGroupBase {
  type: AggregationGroupType.TERMS;
}

export interface HistogramAggregationGroup extends AggregationGroupBase {
  type: AggregationGroupType.HISTOGRAM;
  interval: number;
}

export interface DateHistogramAggregationGroup
  extends AggregationGroupBase {
  type: AggregationGroupType.DATE_HISTOGRAM;
  unit: AggregationDateUnit;
  timeZone?: string;
}

export type AggregationGroup =
  | TermsAggregationGroup
  | HistogramAggregationGroup
  | DateHistogramAggregationGroup;

export interface FieldAggregationExpression {
  type?: AggregationExpressionType.FIELD;
  field: LogicalField;
}

export type AggregationExpression = FieldAggregationExpression;

export interface CountAggregationMetric {
  type: AggregationMetricType.COUNT;
  alias: string;
}

export interface NumericAggregationMetric {
  type: AggregationMetricType.NUMERIC;
  function: AggregationFunction;
  expression: AggregationExpression;
  alias: string;
}

export type AggregationMetric =
  | CountAggregationMetric
  | NumericAggregationMetric;

export interface AggregationQuery<FIELDS extends string = string> {
  filter?: FilterExpression<FIELDS>;
  elements?: AggregationElement[];
  groupBy?: AggregationGroup[];
  metrics: [AggregationMetric, ...AggregationMetric[]];
  sort?: FieldSort[];
  limit?: number;
}
