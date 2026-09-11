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

import { expect, it } from 'vitest';
import { aggregation, filter, SortDirection } from '@ahoo-wang/fetcher-wow';
import { createOrderSource } from '../examples/react/sales-order/querySource.js';
it('groups actual order rows and applies alias sorting and limit after count and sum', async () => {
  const source = createOrderSource(() => [
    { region: 'east', amount: 12 },
    { region: 'west', amount: 8 },
    { region: 'east', amount: 6 },
  ]);
  const query = {
    filter: filter.matchAll(),
    groupBy: [aggregation.terms('region', 'area')],
    metrics: [
      aggregation.count('orders'),
      aggregation.sum(aggregation.field('amount'), 'revenue'),
    ],
    sort: [{ field: 'revenue', direction: SortDirection.DESC }],
    limit: 1,
  };
  expect(await source.aggregate(query)).toEqual([
    { area: 'east', orders: 2, revenue: 18 },
  ]);
});
