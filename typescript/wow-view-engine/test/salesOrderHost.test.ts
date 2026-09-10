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
import { filter, SortDirection } from '@ahoo-wang/fetcher-wow';
import { createOrderService } from '../examples/react/sales-order/service.js';
import { createOrderSource } from '../examples/react/sales-order/querySource.js';
import { createOrderHost } from '../examples/react/sales-order/host.js';
import { orderDefinition } from '../examples/react/sales-order/views.js';
it('queries the same store after release and matches one item rather than different items', async () => {
  const service = createOrderService();
  const source = createOrderSource(service.read);
  const query = {
    filter: filter.eq('state.deliveryOpen', true),
    pagination: { index: 1, size: 50 },
  };
  expect(
    (await source.paged(query)).list.some(
      o => o.aggregateId === 'SO-202609-1002',
    ),
  ).toBe(false);
  await service.execute(
    { type: 'release', orderId: 'SO-202609-1002' },
    'manager',
    'release',
  );
  expect(
    (await source.paged(query)).list.some(
      o => o.aggregateId === 'SO-202609-1002',
    ),
  ).toBe(true);
  const result = await source.paged({
    filter: filter.elementMatch(
      'state.items',
      filter.and([
        filter.eq('productName', '办公显示器'),
        filter.gte('quantity', 5),
      ]),
    ),
    pagination: { index: 1, size: 50 },
    sort: [{ field: 'aggregateId', direction: SortDirection.ASC }],
  });
  expect(result.list.map(o => o.aggregateId)).toEqual([
    'SO-202609-1001',
    'SO-202609-1002',
    'SO-202609-1007',
  ]);
});
it('preserves personal views on reopening but prevents system edits and shared writes by sales', async () => {
  const service = createOrderService();
  const store = new Map<string, string | null>();
  const host = createOrderHost(service, 'sales', 'all', { store });
  const list = await host.instance.list(orderDefinition.id);
  const first = list.instances[0];
  await expect(
    host.instance.save({ ...first, title: '不可修改' }),
  ).rejects.toThrow();
  const input = {
    definitionId: first.definitionId,
    title: first.title,
    kind: first.kind,
    scope: first.scope,
    config: first.config,
  };
  const personal = await host.instance.create(
    { ...input, title: '我的交付', scope: { type: 'personal' } },
    { requestId: 'save-1' },
  );
  const reopened = createOrderHost(service, 'sales', 'all', { store });
  expect((await reopened.instance.load(personal.id)).title).toBe('我的交付');
  await expect(
    host.instance.create(
      { ...input, scope: { type: 'public', source: 'shared' } },
      { requestId: 'shared' },
    ),
  ).rejects.toThrow();
  const saved = await host.instance.save({
    ...personal,
    title: '我的重点交付',
  });
  await expect(host.instance.save(personal)).rejects.toThrow();
  expect(saved.title).toBe('我的重点交付');
});
