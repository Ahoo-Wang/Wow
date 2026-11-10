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

/** `filter`'s full-text search, at the root and inside `elementMatch`. */

import {
  filter,
  FilterOperator,
  SearchMode,
  type ElementFilterExpression,
} from '../../../src/dsl.js';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('builds search filters with explicit defaults and phrase mode', () => {
    expect(filter.search('wow')).toEqual({
      op: FilterOperator.SEARCH,
      query: 'wow',
      mode: SearchMode.TERMS,
      fields: [],
    });
    expect(
      filter.search('event sourcing', {
        mode: SearchMode.PHRASE,
        fields: ['state.title', 'state.description'],
      }),
    ).toEqual({
      op: FilterOperator.SEARCH,
      query: 'event sourcing',
      mode: SearchMode.PHRASE,
      fields: ['state.title', 'state.description'],
    });
  });

  const assertOldSearchSignatureRemoved = () => {
    // @ts-expect-error The new Filter DSL does not retain rest-field compatibility.
    filter.search('wow', 'state.name');
  };
  expectTypeOf(assertOldSearchSignatureRemoved).toBeFunction();

  it('rejects the removed runtime search signature', () => {
    expect(() =>
      Reflect.apply(filter.search, null, ['wow', 'state.name']),
    ).toThrow(TypeError);
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'search without fields',
      create: () => filter.search('wow'),
      expected: {
        op: FilterOperator.SEARCH,
        query: 'wow',
        mode: SearchMode.TERMS,
        fields: [],
      },
    },
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
  });

  it('takes a SEARCH that names element fields inside ELEMENT_MATCH', () => {
    const search = filter.search('usb cable', {
      fields: ['productName', 'note'],
      mode: SearchMode.PHRASE,
    });
    expectTypeOf(search).toMatchTypeOf<
      ElementFilterExpression<'productName' | 'note'>
    >();
    expect(filter.elementMatch('state.items', search)).toEqual({
      op: FilterOperator.ELEMENT_MATCH,
      field: 'state.items',
      predicate: {
        op: FilterOperator.SEARCH,
        query: 'usb cable',
        mode: SearchMode.PHRASE,
        fields: ['productName', 'note'],
      },
    });
    // Nested in a logical predicate too.
    expect(() =>
      filter.elementMatch(
        'state.items',
        filter.and([
          filter.gt('quantity', 0),
          filter.search('usb', { fields: ['productName'] }),
        ]),
      ),
    ).not.toThrow();

    const fields: string[] = ['productName'];
    const unproven = () => {
      // @ts-expect-error Fields not known to be non-empty may be a model-wide search.
      filter.elementMatch('state.items', filter.search('usb', { fields }));
    };
    expectTypeOf(unproven).toBeFunction();
    // An empty list is a model-wide search, whatever the types say.
    expect(() =>
      filter.elementMatch(
        'state.items',
        filter.search('usb', {
          fields: [],
        }) as unknown as ElementFilterExpression,
      ),
    ).toThrow('ELEMENT_MATCH predicate cannot contain root filters.');
  });

  it.each([
    ['blank search query', () => filter.search(' ')],
    ['non-string search query', () => Reflect.apply(filter.search, null, [1])],
    [
      'invalid search mode',
      () => Reflect.apply(filter.search, null, ['wow', { mode: 'INVALID' }]),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});
