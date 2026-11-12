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
import { asc, cursorQuery, filter, SortDirection } from '../../src/dsl.js';

describe('cursorQuery', () => {
  it('creates a cursor request that leaves the page size to the server', () => {
    const query = cursorQuery({ filter: filter.matchAll() });

    expect(query).toEqual({
      filter: { op: 'match-all' },
      projection: {},
      sort: [],
      cursor: null,
    });
    expect(JSON.parse(JSON.stringify(query))).not.toHaveProperty('size');
  });

  it('matches every record when no filter is given', () => {
    expect(cursorQuery().filter).toEqual({ op: 'match-all' });
    expect(cursorQuery({ size: 5 })).toMatchObject({
      filter: { op: 'match-all' },
      size: 5,
    });
  });

  it('rejects a null filter', () => {
    expect(() => cursorQuery({ filter: null as unknown as undefined })).toThrow(
      'filter cannot be null.',
    );
  });

  it('isolates the default projection between cursor requests', () => {
    const first = cursorQuery({ filter: filter.matchAll() });
    first.projection!.include = ['id'];

    const second = cursorQuery({ filter: filter.matchAll() });

    expect(second.projection).toEqual({});
  });

  it('preserves the opaque cursor and ordered sort fields', () => {
    const query = cursorQuery({
      filter: filter.eq('state.status', 'PAID'),
      projection: { include: ['state.status'] },
      sort: [asc('state.createdAt')],
      size: 20,
      cursor: 'opaque-next-cursor',
    });

    expect(query).toEqual({
      filter: { op: 'eq', field: 'state.status', value: 'PAID' },
      projection: { include: ['state.status'] },
      sort: [{ field: 'state.createdAt', direction: 'asc' }],
      size: 20,
      cursor: 'opaque-next-cursor',
    });
  });

  it.each([0, 2_147_483_647])('rejects invalid cursor size %s', size => {
    expect(() => cursorQuery({ filter: filter.matchAll(), size })).toThrow(
      'size must be between 1 and 2147483646.',
    );
  });

  it('rejects more than 32 sort fields', () => {
    expect(() =>
      cursorQuery({
        filter: filter.matchAll(),
        sort: Array.from({ length: 33 }, (_, index) => asc(`field${index}`)),
      }),
    ).toThrow('sort must contain at most 32 fields.');
  });
});

describe('sort uniqueness', () => {
  it('refuses the same field twice', () => {
    // A cursor is a position in one total order. Two directions for one field
    // leave that position ambiguous, and a page can repeat or skip rows.
    expect(() =>
      cursorQuery({
        filter: filter.matchAll(),
        sort: [
          { field: 'createdAt', direction: SortDirection.ASC },
          { field: 'createdAt', direction: SortDirection.DESC },
        ],
      }),
    ).toThrow('Cursor sort fields must be unique.');
  });

  it('admits distinct fields', () => {
    expect(
      cursorQuery({
        filter: filter.matchAll(),
        sort: [
          { field: 'createdAt', direction: SortDirection.ASC },
          { field: 'id', direction: SortDirection.ASC },
        ],
      }).sort,
    ).toHaveLength(2);
  });
});
