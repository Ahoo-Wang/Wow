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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
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
    ['IN', FilterOperator.IN],
    ['NOT_IN', FilterOperator.NOT_IN],
    ['CONTAINS_ALL', FilterOperator.CONTAINS_ALL],
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

  it('asks whether there are any entries at all', () => {
    // `IS_NULL` cannot answer this: a field can hold an empty list without
    // being absent, and they are different questions.
    expect(compile('tags', 'IS_EMPTY', null)).toEqual({
      op: FilterOperator.IS_EMPTY,
      field: 'tags',
    });
  });

  it('still answers the presence questions every kind answers', () => {
    expect(compile('tags', 'IS_NULL', null)).toEqual({
      op: FilterOperator.IS_NULL,
      field: 'tags',
    });
  });

  it('takes numbers as well as text', () => {
    expect(compile('tags', 'IN', [1, 2])).toMatchObject({ values: [1, 2] });
  });

  it('refuses entries that are neither', () => {
    expect(
      errors(
        validateFilter(
          fields,
          tree('tags', 'IN', [{}] as never),
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
          tree('categories', 'IN', ['a']),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(
      errors(
        validateFilter(
          fields,
          tree('categories', 'IN', ['zz']),
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
          tree('tags', 'IN', ['anything']),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('treats no entries as a question not yet asked', () => {
    // Consistent with every other kind since unfinished conditions landed:
    // an empty list is not an error and does not reach the query.
    expect(
      errors(validateFilter(fields, tree('tags', 'IN', []), builtinFieldKinds)),
    ).toEqual([]);
    expect(compile('tags', 'IN', [])).toEqual({ op: FilterOperator.MATCH_ALL });
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

    expect(builtinFieldKinds.get('array')!.editor('IN', field)).toEqual(want);
  });

  it('starts with no entries chosen', () => {
    const field = fields[0];

    expect(builtinFieldKinds.get('array')!.emptyValue('IN', field)).toEqual([]);
  });

  it('needs no input for the emptiness questions', () => {
    const field = fields[0];
    const kind = builtinFieldKinds.get('array')!;

    expect(kind.editor('IS_EMPTY', field)).toEqual({ input: 'none' });
    expect(kind.editor('IS_NULL', field)).toEqual({ input: 'none' });
  });

  it.each([
    ['IN', 'Categories has any of Apparel'],
    ['NOT_IN', 'Categories has none of Apparel'],
    ['CONTAINS_ALL', 'Categories has all of Apparel'],
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
        tree('tags', 'IS_EMPTY', null),
        builtinFieldKinds,
      )[0].text,
    ).toBe('Tags has no entries');
  });
});
