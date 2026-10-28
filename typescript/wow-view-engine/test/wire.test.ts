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
  AggregationGroupType,
  FilterOperator,
  SortDirection,
  AggregationDatePart,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { AnalysisGroup } from '../src/model/analysis';
import {
  AggregationGroupTypes,
  FilterOperators,
  SortDirections,
} from '../src/model/wire';

/** A switch over a config union, every case read from a view. */
function groupWord(group: AnalysisGroup): string {
  switch (group.type) {
    case AggregationGroupTypes.TERMS:
      return group.field;
    case AggregationGroupTypes.HISTOGRAM:
      return String(group.interval);
    case AggregationGroupTypes.DATE_HISTOGRAM:
      return group.unit;
    case AggregationGroupTypes.DATE_PART:
      return group.part;
  }
}

describe('the literal views of the wire values', () => {
  it('are the wow-client enums themselves, so a value read there is the enum value', () => {
    expect(AggregationGroupTypes).toBe(AggregationGroupType);
    expect(FilterOperators).toBe(FilterOperator);
    expect(SortDirections.DESC).toBe(SortDirection.DESC);
  });

  // wire spelling (D352b): the subject is that a config written as plain JSON strings type-checks
  it('are typed by their values, so a config stays plain JSON', () => {
    expectTypeOf(AggregationGroupTypes.TERMS).toEqualTypeOf<'TERMS'>();
    // A config is written as strings and still type-checks.
    const terms: AnalysisGroup = {
      type: 'terms',
      field: 'city',
      alias: 'c',
    };
    expectTypeOf(terms).toExtend<AnalysisGroup>();
  });

  it('keep a switch over a config union exhaustive', () => {
    // groupWord compiles with no default and no trailing return only because
    // its cases narrow the union to nothing (tsconfig.test.json checks it).
    expect(
      groupWord({
        type: AggregationGroupType.DATE_PART,
        field: 'at',
        alias: 'p',
        part: AggregationDatePart.HOUR_OF_DAY,
      }),
    ).toBe(AggregationDatePart.HOUR_OF_DAY);
  });
});
