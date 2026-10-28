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
  FilterOperator,
  HavingExpressionType,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  validateFilter,
} from '../src/filter/index.js';
import type {
  FieldDefinition,
  FilterOperatorName,
  FilterTree,
  FilterValue,
} from '../src/model/index.js';

/**
 * A field holding several values at once. Its operators mean set relations
 * rather than equality, which is why it is a kind of its own: on a scalar
 * field `IN` asks whether the one value is among those listed, and here it
 * asks whether the field's entries include any of them.
 */
const fields: FieldDefinition[] = [
  { name: 'tags', label: 'Tags', kind: 'array' },
  {
    name: 'categories',
    label: 'Categories',
    kind: 'array',
    options: [
      { value: 'a', label: 'Apparel' },
      { value: 'b', label: 'Books' },
    ],
  },
  { name: 'owners', label: 'Owners', kind: 'array', remote: 'users' },
];

const context = { now: new Date('2026-09-17T00:00:00Z'), timeZone: 'UTC' };

function tree(
  field: string,
  operator: FilterOperatorName,
  value: FilterValue,
): FilterTree {
  return { op: 'and', children: [{ field, operator, value }] };
}

function errors(issues: { severity: string; code: string }[]): string[] {
  return issues.filter(i => i.severity === 'error').map(i => i.code);
}

function compile(
  field: string,
  operator: FilterOperatorName,
  value: FilterValue,
) {
  return compileFilter(
    fields,
    tree(field, operator, value),
    builtinFieldKinds,
    context,
  );
}

describe('the array kind', () => {
  it.each([
    [FilterOperator.IN, FilterOperator.IN],
    [FilterOperator.NOT_IN, FilterOperator.NOT_IN],
    [FilterOperator.CONTAINS_ALL, FilterOperator.CONTAINS_ALL],
  ] as [FilterOperatorName, FilterOperator][])(
    'compiles %s over the entries',
    (operator, op) => {
      expect(compile('tags', operator, ['x', 'y'])).toEqual({
        op,
        field: 'tags',
        values: ['x', 'y'],
      });
    },
  );

  /**
   * Two questions of emptiness, not five (second review R1-P1-7): absent,
   * null and `[]` are one answer to whoever reads a list. MongoDB's
   * `$size: 0` alone misses the absent and the null ones, so 「没有条目」
   * asks for either.
   */
  it('asks whether there are any entries at all, whichever way there are none', () => {
    const none = [
      { op: FilterOperator.IS_EMPTY, field: 'tags' },
      { op: FilterOperator.IS_NULL, field: 'tags' },
    ];
    expect(compile('tags', FilterOperator.IS_EMPTY, null)).toEqual({
      op: FilterOperator.OR,
      operands: none,
    });
    expect(compile('tags', FilterOperator.IS_NOT_NULL, null)).toEqual({
      op: FilterOperator.NOR,
      operands: none,
    });
  });

  it('offers no other question of presence', () => {
    const kind = builtinFieldKinds.get('array')!;
    expect(kind.operators).toEqual([
      FilterOperator.IN,
      FilterOperator.NOT_IN,
      FilterOperator.CONTAINS_ALL,
      FilterOperator.IS_EMPTY,
      FilterOperator.IS_NOT_NULL,
    ]);
    // Offered only where the service answers both of what it is sent as.
    expect(kind.compiledOperators?.(FilterOperator.IS_NOT_NULL)).toEqual([
      FilterOperator.IS_EMPTY,
      FilterOperator.IS_NULL,
    ]);
    expect(kind.compiledOperators?.(FilterOperator.IN)).toEqual([
      HavingExpressionType.IN,
    ]);
  });

  it('takes numbers as well as text', () => {
    expect(compile('tags', FilterOperator.IN, [1, 2])).toMatchObject({
      values: [1, 2],
    });
  });

  it('refuses entries that are neither', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree('tags', FilterOperator.IN, [{}] as never),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.expected-entry-list']);
  });

  it('closes the set when the definition declares one', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree('categories', FilterOperator.IN, ['a']),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(
      errors(
        validateFilter(
          fields,
          tree('categories', FilterOperator.IN, ['zz']),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['filter.value.unknown-option']);
  });

  it('leaves it open when the definition declares none', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree('tags', FilterOperator.IN, ['anything']),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('treats no entries as a question not yet asked', () => {
    // Consistent with every other kind since unfinished conditions landed:
    // an empty list is not an error and does not reach the query.
    expect(
      errors(
        validateFilter(
          fields,
          tree('tags', FilterOperator.IN, []),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(compile('tags', FilterOperator.IN, [])).toEqual({
      op: FilterOperator.MATCH_ALL,
    });
  });

  it.each([
    ['tags', { input: 'text', multiple: true }],
    [
      'categories',
      {
        input: 'select',
        multiple: true,
        options: [
          { value: 'a', label: 'Apparel' },
          { value: 'b', label: 'Books' },
        ],
      },
    ],
    ['owners', { input: 'remote', multiple: true, remote: 'users' }],
  ])('offers the editor %s has candidates for', (name, want) => {
    const field = fields.find(entry => entry.name === name)!;

    expect(
      builtinFieldKinds.get('array')!.editor(FilterOperator.IN, field),
    ).toEqual(want);
  });

  it('starts with no entries chosen', () => {
    const field = fields[0];

    expect(
      builtinFieldKinds.get('array')!.emptyValue(FilterOperator.IN, field),
    ).toEqual([]);
  });

  it('needs no input for the emptiness questions', () => {
    const field = fields[0];
    const kind = builtinFieldKinds.get('array')!;

    expect(kind.editor(FilterOperator.IS_EMPTY, field)).toEqual({
      input: 'none',
    });
    expect(kind.editor(FilterOperator.IS_NOT_NULL, field)).toEqual({
      input: 'none',
    });
  });

  it.each([
    [FilterOperator.IS_EMPTY, 'has-no-entries', 'Categories has no entries'],
    [FilterOperator.IS_NOT_NULL, 'has-entries', 'Categories has entries'],
  ] as [FilterOperatorName, string, string][])(
    'says %s as a question of entries',
    (operator, relation, want) => {
      const [item] = describeFilter(
        fields,
        tree('categories', operator, null),
        builtinFieldKinds,
      );
      expect(item.text).toBe(want);
      expect(item.relation).toBe(relation);
    },
  );

  it.each([
    [FilterOperator.IN, 'Categories has any of Apparel'],
    [FilterOperator.NOT_IN, 'Categories has none of Apparel'],
    [FilterOperator.CONTAINS_ALL, 'Categories has all of Apparel'],
  ] as [FilterOperatorName, string][])('summarises %s', (operator, want) => {
    // Declared candidates are summarised by their label, not their value.
    expect(
      describeFilter(
        fields,
        tree('categories', operator, ['a']),
        builtinFieldKinds,
      )[0].text,
    ).toBe(want);
  });

  it('summarises the emptiness questions without a value', () => {
    expect(
      describeFilter(
        fields,
        tree('tags', FilterOperator.IS_EMPTY, null),
        builtinFieldKinds,
      )[0].text,
    ).toBe('Tags has no entries');
  });
});
