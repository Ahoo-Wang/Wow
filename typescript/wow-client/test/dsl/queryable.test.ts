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

import { describe, expect, expectTypeOf, it } from 'vitest';
import type {
  FieldSort,
  FilterListQuery,
  FilterPagedQuery,
  FilterSingleQuery,
  Projection,
} from '../../src';
import {
  asc,
  DEFAULT_PAGINATION,
  filter,
  listQuery,
  pagedList,
  pagedQuery,
  singleQuery,
} from '../../src';

describe('queryable', () => {
  it('builds FilterExpression queries, with or without arguments', () => {
    expectTypeOf(singleQuery()).toEqualTypeOf<FilterSingleQuery<string>>();
    expectTypeOf(listQuery({ limit: 10 })).toEqualTypeOf<
      FilterListQuery<string>
    >();
    expectTypeOf(pagedQuery()).toEqualTypeOf<FilterPagedQuery<string>>();
    expectTypeOf(
      listQuery({ filter: filter.eq('state.status', 'PAID') }),
    ).toEqualTypeOf<FilterListQuery<'state.status'>>();
  });

  it('refuses a condition, which only the legacy entry builds', () => {
    // @ts-expect-error The root factories take a filter, not a Condition.
    singleQuery({ condition: { operator: 'ALL' } });
  });

  it('matches everything when no filter is given', () => {
    expect(singleQuery()).toEqual({ filter: filter.matchAll() });
    expect(listQuery()).toEqual({ filter: filter.matchAll() });
    expect(pagedQuery()).toEqual({
      filter: filter.matchAll(),
      pagination: DEFAULT_PAGINATION,
    });
  });

  it('refuses a null filter instead of falling back to match-all', () => {
    for (const createQuery of [singleQuery, listQuery, pagedQuery]) {
      expect(() => createQuery({ filter: null } as never)).toThrowError(
        'filter cannot be null.',
      );
    }
  });

  it('leaves the list limit to the server unless one is given', () => {
    const expression = filter.eq('state.status', 'PAID');
    expect(listQuery({ filter: expression })).not.toHaveProperty(
      'limit',
      expect.anything(),
    );
    expect(listQuery({ filter: expression, limit: 20 })).toEqual({
      filter: expression,
      limit: 20,
    });
  });

  it('passes projection and sort through', () => {
    const queryProjection: Projection = { include: ['field1', 'field2'] };
    const sort: FieldSort[] = [asc('name')];
    const expression = filter.eq('name', 'test');
    expect(
      singleQuery({ filter: expression, projection: queryProjection, sort }),
    ).toEqual({ filter: expression, projection: queryProjection, sort });
    expect(
      listQuery({ filter: expression, projection: queryProjection, sort }),
    ).toEqual({ filter: expression, projection: queryProjection, sort });
    const pagination = { index: 2, size: 20 };
    expect(
      pagedQuery({
        filter: expression,
        projection: queryProjection,
        sort,
        pagination,
      }),
    ).toEqual({
      filter: expression,
      projection: queryProjection,
      sort,
      pagination,
    });
  });

  it('never hands out the shared default pagination', () => {
    const query = pagedQuery();
    expect(query.pagination).not.toBe(DEFAULT_PAGINATION);
    expect(Object.isFrozen(DEFAULT_PAGINATION)).toBe(true);
  });

  describe('pagedList', () => {
    it('defaults to an empty page', () => {
      expect(pagedList()).toEqual({ total: 0, list: [] });
      expect(pagedList({})).toEqual({ total: 0, list: [] });
    });

    it('gives every empty page its own list', () => {
      const first = pagedList<number>();
      first.list.push(1);
      expect(pagedList<number>().list).toEqual([]);
    });

    it('counts the list when no total is given', () => {
      const list = [{ id: 1, name: 'test' }];
      expect(pagedList({ list })).toEqual({ total: 1, list });
      expect(pagedList({ total: 10, list })).toEqual({ total: 10, list });
      expect(pagedList({ total: 10 })).toEqual({ total: 10, list: [] });
    });
  });
});
