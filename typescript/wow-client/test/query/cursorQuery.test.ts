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
import { asc, cursorQuery, filter } from '../../src';

describe('cursorQuery', () => {
  it('creates a Wow V9 cursor request with server defaults', () => {
    const query = cursorQuery({ filter: filter.matchAll() });

    expect(query).toEqual({
      filter: { op: 'MATCH_ALL' },
      projection: {},
      sort: [],
      size: 10,
      cursor: null,
    });
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
      filter: { op: 'EQ', field: 'state.status', value: 'PAID' },
      projection: { include: ['state.status'] },
      sort: [{ field: 'state.createdAt', direction: 'ASC' }],
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
