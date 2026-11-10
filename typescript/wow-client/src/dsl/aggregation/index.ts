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

// admit.ts, aliases.ts and sort.ts are internal and stay unlisted; the
// modules of the query's parts are listed by name, so their input checks
// (groupInput, groupScope, numeric, edge) and the derived and having
// builders stay internal.
export * from './types.js';
export {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationGroupScope,
  AggregationGroupType,
  type AggregationGroup,
  type AggregationGroupInput,
  type AggregationGroupScopeOptions,
  type DateHistogramAggregationGroup,
  type DateHistogramAggregationOptions,
  type DatePartAggregationGroup,
  type DatePartAggregationOptions,
  type HistogramAggregationGroup,
  type HistogramAggregationOptions,
  type TermsAggregationGroup,
  type TermsAggregationOptions,
} from './groups.js';
export {
  AggregationFunction,
  AggregationMetricType,
  type AggregationMetric,
  type AggregationMetricOptions,
  type AnyAggregationMetric,
  type CountAggregationMetric,
  type DistinctCountAggregationMetric,
  type EdgeAggregationMetric,
  type EdgeAggregationOptions,
  type NumericAggregationMetric,
  type PercentileAggregationMetric,
  type PercentileAggregationOptions,
} from './metrics.js';
export {
  DerivedExpressionType,
  RunningFunction,
  WindowFunction,
  type DerivedAggregationMetric,
  type DerivedExpression,
  type DerivedExpressionDsl,
  type DerivedWindow,
} from './derived.js';
export {
  HavingExpressionType,
  type HavingDsl,
  type HavingExpression,
} from './having.js';
export { aggregation } from './builders.js';
