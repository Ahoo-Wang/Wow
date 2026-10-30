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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationFunction,
  AggregationGroupScope,
  ComparisonOperator,
  DateDiffUnit,
  DeletionState,
  FilterOperator,
  PagingMode,
  QueryValueKind,
  RunningFunction,
  SearchMode,
  SensitivityLevel,
  SortDirection,
  StringComparison,
  WindowFunction,
  type TotalMode,
} from '../src/dsl.js';
import {
  CommandResultStatus,
  FunctionKind,
  Stage,
  type Ownership,
} from '../src/wire.js';

/**
 * The wire names the JVM writes for each Wow enumeration this client declares,
 * written by `TypeScriptWireNamesTest` of `:api`. Regenerate it, never edit it:
 * `./gradlew :api:test --tests "*TypeScriptWireNamesTest" --rerun -Dwow.snapshot.update=true`.
 */
const fixture = JSON.parse(
  readFileSync(new URL('./fixtures/wire-names.json', import.meta.url), 'utf8'),
) as { enumerations: Record<string, string[]> };

// The values of a type that has no value of its own, each named once: a value
// the type lacks, or one left out here, fails the type check.
const OWNERSHIP = { owner: true, tenant: true } satisfies Record<
  Ownership,
  true
>;
const TOTAL_MODE = { exact: true, bounded: true, none: true } satisfies Record<
  TotalMode,
  true
>;

/** Each enumeration by its Kotlin name, with the values this client declares for it. */
const DECLARED: Record<string, readonly string[]> = {
  'CommandResult.Status': Object.values(CommandResultStatus),
  Stage: Object.values(Stage),
  FunctionKind: Object.values(FunctionKind),
  Ownership: Object.keys(OWNERSHIP),
  FilterOperator: Object.values(FilterOperator),
  StringComparison: Object.values(StringComparison),
  SearchMode: Object.values(SearchMode),
  DeletionState: Object.values(DeletionState),
  'Sort.Direction': Object.values(SortDirection),
  TotalMode: Object.keys(TOTAL_MODE),
  PagingMode: Object.values(PagingMode),
  QueryValueKind: Object.values(QueryValueKind),
  AggregationDatePart: Object.values(AggregationDatePart),
  AggregationDateUnit: Object.values(AggregationDateUnit),
  DateDiffUnit: Object.values(DateDiffUnit),
  AggregationExpressionOperator: Object.values(AggregationExpressionOperator),
  WindowFunction: Object.values(WindowFunction),
  RunningFunction: Object.values(RunningFunction),
  ComparisonOperator: Object.values(ComparisonOperator),
  AggregationFunction: Object.values(AggregationFunction),
  AggregationGroupScope: Object.values(AggregationGroupScope),
  SensitivityLevel: Object.values(SensitivityLevel),
};

/**
 * A server and this client must spell every value of Wow's enumerations
 * alike: a client that compares `SUCCEEDED` to a server's `succeeded` reads a
 * success as an unknown status. The fixture is what the JVM writes, so a value
 * renamed or added there fails here until this client follows.
 */
describe('the wire names of Wow enumerations', () => {
  it('should be declared for exactly the enumerations the JVM lists', () => {
    expect(Object.keys(DECLARED).sort()).toEqual(
      Object.keys(fixture.enumerations).sort(),
    );
  });

  it.each(Object.entries(fixture.enumerations))(
    'should be what the JVM writes for %s',
    (name, written) => {
      expect([...(DECLARED[name] ?? [])].sort()).toEqual([...written].sort());
    },
  );
});
