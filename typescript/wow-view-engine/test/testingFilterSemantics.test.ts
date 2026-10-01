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

import { describe, expect, it } from 'vitest';
import {
  FilterOperator,
  StringComparison,
  type FilterExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData } from '../src/index.js';
import { matches, memorySource } from '../src/testing/index.js';

/**
 * Wow's semantics matrix, case for case: `FilterSemantics.CASES` in
 * `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/FilterSemantics.kt`,
 * which the TCK (`test/wow-tck/.../FilterSemanticsMatrix.kt`) runs against
 * every backend with one stored record per probe. The expected matches are
 * MongoDB's, the canonical backend. A change there is copied here.
 */

/** A probe's stored value; `MISSING` leaves the field out of the record. */
const MISSING = Symbol('missing');

type Shape = 'string' | 'number' | 'array';

/** `SemanticProbe.ALL`, and the state field each shape is stored in. */
const PROBES: Record<
  Shape,
  { field: string; values: Record<string, unknown> }
> = {
  string: {
    field: 'state.data',
    values: {
      missing: MISSING,
      null: null,
      empty: '',
      value: 'Wow',
      caseVariant: 'wow',
      other: 'Other',
    },
  },
  number: {
    field: 'state.createdAt',
    values: { missing: MISSING, null: null, one: 1, two: 2, three: 3 },
  },
  array: {
    field: 'state.keywords',
    values: {
      missing: MISSING,
      null: null,
      empty: [],
      value: ['Wow'],
      valueAndOther: ['Wow', 'Other'],
      other: ['Other'],
    },
  },
};

type Case = [
  id: string,
  shape: Shape,
  matches: string[],
  filter: (field: string) => FilterExpression,
];

const f = (filter: Record<string, unknown>) => filter as FilterExpression;
const text =
  (op: FilterOperator, value: string, ignoreCase = false) =>
  (field: string) =>
    f({
      op,
      field,
      value,
      ...(ignoreCase
        ? { stringComparison: StringComparison.CASE_INSENSITIVE }
        : {}),
    });
const valued = (op: FilterOperator, value: unknown) => (field: string) =>
  f({ op, field, value });
const listed = (op: FilterOperator, values: unknown[]) => (field: string) =>
  f({ op, field, values });
const bare = (op: FilterOperator) => (field: string) => f({ op, field });

/** `FilterSemantics.CASES`. */
const CASES: Case[] = [
  // Equality is exact and case-sensitive; negations match records without the field.
  ['string.eq', 'string', ['value'], valued(FilterOperator.EQ, 'Wow')],
  [
    'string.eq-null',
    'string',
    ['missing', 'null'],
    valued(FilterOperator.EQ, null),
  ],
  [
    'string.ne',
    'string',
    ['missing', 'null', 'empty', 'caseVariant', 'other'],
    valued(FilterOperator.NE, 'Wow'),
  ],
  [
    'string.ne-null',
    'string',
    ['empty', 'value', 'caseVariant', 'other'],
    valued(FilterOperator.NE, null),
  ],
  [
    'string.in',
    'string',
    ['value', 'other'],
    listed(FilterOperator.IN, ['Wow', 'Other']),
  ],
  [
    'string.not-in',
    'string',
    ['missing', 'null', 'empty', 'caseVariant'],
    listed(FilterOperator.NOT_IN, ['Wow', 'Other']),
  ],
  // Literal matches: case-sensitive unless asked otherwise; never match a missing or null field.
  [
    'string.contains',
    'string',
    ['value', 'caseVariant'],
    text(FilterOperator.CONTAINS, 'o'),
  ],
  [
    'string.contains-ignore-case',
    'string',
    ['value', 'caseVariant', 'other'],
    text(FilterOperator.CONTAINS, 'o', true),
  ],
  [
    'string.starts-with',
    'string',
    ['value'],
    text(FilterOperator.STARTS_WITH, 'W'),
  ],
  [
    'string.starts-with-ignore-case',
    'string',
    ['value', 'caseVariant'],
    text(FilterOperator.STARTS_WITH, 'W', true),
  ],
  ['string.ends-with', 'string', [], text(FilterOperator.ENDS_WITH, 'OW')],
  [
    'string.ends-with-ignore-case',
    'string',
    ['value', 'caseVariant'],
    text(FilterOperator.ENDS_WITH, 'OW', true),
  ],
  // Presence: null and missing are both "null"; only a missing field does not exist.
  [
    'string.is-null',
    'string',
    ['missing', 'null'],
    bare(FilterOperator.IS_NULL),
  ],
  [
    'string.is-not-null',
    'string',
    ['empty', 'value', 'caseVariant', 'other'],
    bare(FilterOperator.IS_NOT_NULL),
  ],
  [
    'string.exists',
    'string',
    ['null', 'empty', 'value', 'caseVariant', 'other'],
    bare(FilterOperator.EXISTS),
  ],
  ['string.not-exists', 'string', ['missing'], bare(FilterOperator.NOT_EXISTS)],
  [
    'string.is-empty-string',
    'string',
    ['empty'],
    bare(FilterOperator.IS_EMPTY_STRING),
  ],
  [
    'string.is-not-empty-string',
    'string',
    ['value', 'caseVariant', 'other'],
    bare(FilterOperator.IS_NOT_EMPTY_STRING),
  ],
  // Ranges never match a missing or null field.
  ['number.eq', 'number', ['two'], valued(FilterOperator.EQ, 2)],
  [
    'number.ne',
    'number',
    ['missing', 'null', 'one', 'three'],
    valued(FilterOperator.NE, 2),
  ],
  ['number.in', 'number', ['one', 'three'], listed(FilterOperator.IN, [1, 3])],
  ['number.gt', 'number', ['two', 'three'], valued(FilterOperator.GT, 1)],
  ['number.gte', 'number', ['two', 'three'], valued(FilterOperator.GTE, 2)],
  ['number.lt', 'number', ['one'], valued(FilterOperator.LT, 2)],
  ['number.lte', 'number', ['one', 'two'], valued(FilterOperator.LTE, 2)],
  [
    'number.between',
    'number',
    ['two', 'three'],
    field =>
      f({ op: FilterOperator.BETWEEN, field, lowerBound: 2, upperBound: 3 }),
  ],
  // Arrays: a scalar operand matches any element; an array operand of EQ matches the whole array exactly.
  [
    'array.eq',
    'array',
    ['value', 'valueAndOther'],
    valued(FilterOperator.EQ, 'Wow'),
  ],
  [
    'array.eq-array',
    'array',
    ['valueAndOther'],
    valued(FilterOperator.EQ, ['Wow', 'Other']),
  ],
  [
    'array.ne',
    'array',
    ['missing', 'null', 'empty', 'other'],
    valued(FilterOperator.NE, 'Wow'),
  ],
  [
    'array.in',
    'array',
    ['valueAndOther', 'other'],
    listed(FilterOperator.IN, ['Other']),
  ],
  [
    'array.not-in',
    'array',
    ['missing', 'null', 'empty', 'value'],
    listed(FilterOperator.NOT_IN, ['Other']),
  ],
  [
    'array.contains-all',
    'array',
    ['valueAndOther'],
    listed(FilterOperator.CONTAINS_ALL, ['Wow', 'Other']),
  ],
  [
    'array.contains-all-single',
    'array',
    ['value', 'valueAndOther'],
    listed(FilterOperator.CONTAINS_ALL, ['Wow']),
  ],
  // An empty array is present, not null, and the only empty collection.
  ['array.is-empty', 'array', ['empty'], bare(FilterOperator.IS_EMPTY)],
  ['array.is-null', 'array', ['missing', 'null'], bare(FilterOperator.IS_NULL)],
  [
    'array.is-not-null',
    'array',
    ['empty', 'value', 'valueAndOther', 'other'],
    bare(FilterOperator.IS_NOT_NULL),
  ],
  [
    'array.exists',
    'array',
    ['null', 'empty', 'value', 'valueAndOther', 'other'],
    bare(FilterOperator.EXISTS),
  ],
  ['array.not-exists', 'array', ['missing'], bare(FilterOperator.NOT_EXISTS)],
];

/** One record per probe of a shape, as the TCK seeds them. */
function records(shape: Shape): RecordData[] {
  const [, key] = PROBES[shape].field.split('.');
  return Object.entries(PROBES[shape].values).map(([name, value]) => ({
    aggregateId: `semantic-${shape}-${name}`,
    probe: name,
    state: value === MISSING ? {} : { [key]: value },
  }));
}

describe('the semantics matrix (FilterSemantics)', () => {
  it.each(CASES)('%s', async (_, shape, expected, filter) => {
    const stored = records(shape);
    const condition = filter(PROBES[shape].field);
    const { list } = await memorySource(stored).paged({
      filter: condition,
      pagination: { index: 1, size: 100 },
    });
    expect(new Set(list.map(({ probe }) => probe))).toEqual(new Set(expected));
    // `matches` answers each record the same way.
    expect(
      new Set(
        stored
          .filter(record => matches(record, condition))
          .map(({ probe }) => probe),
      ),
    ).toEqual(new Set(expected));
  });

  it('covers every case of the matrix once', () => {
    expect(new Set(CASES.map(([id]) => id)).size).toBe(CASES.length);
    expect(CASES).toHaveLength(38);
  });
});
