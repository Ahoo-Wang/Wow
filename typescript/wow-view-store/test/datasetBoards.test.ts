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

/**
 * A board over datasets of several services (Wow D238 ⑨, view engine D93)
 * persists through `WowViewStore` as any board does: the store keeps the
 * board and the views its panels name, whatever definitions those are, so
 * a host that runs the view store gets durable cross-service boards with
 * no code of its own.
 */

import { describe, expect, it } from 'vitest';
import {
  emptyDashboardConfig,
  type DashboardViewConfig,
  type ViewConfig,
  type WriteContext,
} from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '../src/index.js';
import { actingAs, installFake, WRITER_ROLES, type Caller } from './support.js';

const ALICE: Caller = {
  user: 'alice',
  tenant: 't1',
  roles: WRITER_ROLES,
  app: 'analytics-catalog-console',
};

const write = (): WriteContext => ({ requestId: `r-${crypto.randomUUID()}` });

const record = { kind: 'record', pageSize: 20 } as unknown as ViewConfig;

describe('a board over datasets of several services, in WowViewStore', () => {
  it('keeps the board and the dataset views its panels name, for the next session', async () => {
    installFake();
    const store = new WowViewStore({ fetcher: actingAs(ALICE) });
    const view = (definitionId: string, title: string) =>
      store.create(
        { definitionId, title, scope: 'shared', config: record },
        write(),
      );
    const orders = await view('shop.orders', 'Shop orders');
    const members = await view('crm.members', 'CRM members');
    const panel = (id: string, instanceId: string, x: number) => ({
      id,
      kind: 'view' as const,
      instanceId,
      bindings: [],
      layout: { x, y: 0, w: 12, h: 4 },
    });
    const board: DashboardViewConfig = {
      ...emptyDashboardConfig(),
      panels: [panel('orders', orders.id, 0), panel('members', members.id, 12)],
    };
    const saved = await store.create(
      {
        definitionId: 'analytics-catalog-boards',
        title: 'Across services',
        scope: 'shared',
        config: board,
      },
      write(),
    );

    // Another session: a new store over the same service.
    const next = new WowViewStore({ fetcher: actingAs(ALICE) });
    const listed = await next.list('analytics-catalog-boards');
    expect(listed.map(summary => summary.id)).toEqual([saved.id]);
    const read = await next.get(saved.id);
    expect(read.config).toEqual(board);
    const named = await Promise.all(
      (read.config as DashboardViewConfig).panels.map(async found =>
        'instanceId' in found && found.instanceId
          ? (await next.get(found.instanceId)).definitionId
          : null,
      ),
    );
    expect(named).toEqual(['shop.orders', 'crm.members']);
  });
});
