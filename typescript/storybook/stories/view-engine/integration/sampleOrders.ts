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

import type { RecordData } from '@ahoo-wang/wow-view-engine';

/*
 * The walkthrough's stand-in for a Wow service: twelve order snapshots, in
 * the shape the snapshot query answers (the envelope beside `state`). In a
 * host, `wowOrderSource` reads them from the service instead.
 */

const WAREHOUSES = ['华东（嘉兴）', '华北（天津）', '华南（东莞）'] as const;
const STATUSES = ['PAID', 'PAID', 'SHIPPED', 'PAID', 'CANCELLED', 'SHIPPED'];
const START = Date.parse('2026-09-21T09:00:00+08:00');

export const SAMPLE_ORDERS: readonly RecordData[] = Array.from(
  { length: 12 },
  (_, index) => ({
    aggregateId: `SO-${String(index + 1).padStart(4, '0')}`,
    firstEventTime: START + index * 47 * 60_000,
    state: {
      status: STATUSES[index % STATUSES.length],
      warehouse: WAREHOUSES[index % WAREHOUSES.length],
      amount: 59 + ((index * 37) % 11) * 20,
    },
  }),
);

/** How many of them the system view 「待发货」 lists. */
export const SAMPLE_TO_SHIP = SAMPLE_ORDERS.filter(
  order => (order.state as { status: string }).status === 'PAID',
).length;
