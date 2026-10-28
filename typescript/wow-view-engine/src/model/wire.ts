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
 * The protocol's values, for every layer of the engine: the one place the
 * rest of the package reads a Wow wire value. No layer writes one as a
 * literal (`test/architecture.test.ts` refuses it); it reads the member
 * here, and when Wow respells its values (D352) they change in
 * `@ahoo-wang/wow-client` and nowhere in this package.
 *
 * Each export is a wow-client enum seen through {@link literals}: the same
 * object, typed by its values rather than by the enum. A config keeps its
 * enums as plain strings (`{ type: 'TERMS' }`), and a `switch` over such a
 * string stays exhaustive only when its cases are typed as strings too —
 * matched against an enum member it narrows each case and loses the last.
 * The constructors of the wow-client DSL (`aggregation.*`, `filter.*`)
 * still take the enums themselves. Internal: no entry exports this module.
 * Should a second package need the same views, they move to wow-client
 * rather than being copied there.
 */

import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DateDiffUnit,
  DeletionState,
  DerivedExpressionType,
  FilterOperator,
  HavingExpressionType,
  PagingMode,
  RunningFunction,
  SearchMode,
  SortDirection,
  StringComparison,
  TimeUnit,
  WindowFunction,
} from '@ahoo-wang/wow-client/dsl';

export {
  QueryConstraintTypes,
  QuerySemanticTypes,
  QueryValueTypes,
} from '@ahoo-wang/wow-client/dsl';

/** An enum's members typed by their values: `{ EQ: 'EQ', … }`. */
export type Literals<E extends Record<string, string>> = {
  readonly [K in keyof E]: `${E[K]}`;
};

/**
 * The enum itself, typed as {@link Literals}: nothing is copied, so a value
 * read here is the enum's value.
 */
export function literals<E extends Record<string, string>>(
  values: E,
): Literals<E> {
  return values as Literals<E>;
}

export const AggregationDateParts = literals(AggregationDatePart);
export const AggregationDateUnits = literals(AggregationDateUnit);
export const AggregationExpressionOperators = literals(
  AggregationExpressionOperator,
);
export const AggregationExpressionTypes = literals(AggregationExpressionType);
export const AggregationFunctions = literals(AggregationFunction);
export const AggregationGroupTypes = literals(AggregationGroupType);
export const AggregationMetricTypes = literals(AggregationMetricType);
export const ComparisonOperators = literals(ComparisonOperator);
export const DateDiffUnits = literals(DateDiffUnit);
export const DeletionStates = literals(DeletionState);
export const DerivedExpressionTypes = literals(DerivedExpressionType);
export const FilterOperators = literals(FilterOperator);
export const HavingExpressionTypes = literals(HavingExpressionType);
export const PagingModes = literals(PagingMode);
export const RunningFunctions = literals(RunningFunction);
export const SearchModes = literals(SearchMode);
export const SortDirections = literals(SortDirection);
export const StringComparisons = literals(StringComparison);
export const TimeUnits = literals(TimeUnit);
export const WindowFunctions = literals(WindowFunction);
