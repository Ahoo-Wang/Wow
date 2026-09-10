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

import { it, expect } from 'vitest';
import { FilterOperator as Op, SortDirection } from '@ahoo-wang/fetcher-wow';
import { createCatalog } from '../examples/react/catalog/catalog.js';

it('filters, sorts and updates the same catalog in both presentations', async () => {
  const catalog = createCatalog();
  const all = await catalog.source.paged!({
    filter: { op: Op.MATCH_ALL },
    pagination: { index: 1, size: 20 },
  });
  expect(all.total).toBe(12);
  expect(new Set(all.list.map(row => row.cover)).size).toBe(12);
  const home = await catalog.source.paged!({
    filter: { op: Op.EQ, field: 'category', value: '家居' },
    sort: [{ field: 'price', direction: SortDirection.DESC }],
    pagination: { index: 1, size: 20 },
  });
  expect(home.total).toBe(6);
  expect(home.list[0].price).toBe(899);
  catalog.update(['SKU-001', 'SKU-002'], { status: 'draft', favorite: true });
  const favorites = await catalog.source.paged!({
    filter: { op: Op.EQ, field: 'favorite', value: true },
  });
  expect(favorites.total).toBe(2);
  expect(favorites.list.every(row => row.status === 'draft')).toBe(true);
  expect(all.list[0].favorite).toBe(false);
  expect(() =>
    catalog.update(['SKU-001', 'missing'], { status: 'published' }),
  ).toThrow();
  const unchanged = await catalog.source.paged!({
    filter: { op: Op.EQ, field: 'favorite', value: true },
  });
  expect(unchanged.list.every(row => row.status === 'draft')).toBe(true);
});
