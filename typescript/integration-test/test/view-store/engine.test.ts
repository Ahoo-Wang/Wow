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
 * The view engine on `WowViewStore`, end to end (view-store-backend.md 7
 * 「引擎端到端」): the engine's own commands — save, save as, a conflict
 * reloaded or overwritten, an unknown outcome retried, setting a view shared
 * — against each view store server, so the write ledger meets a real
 * server's versions, request ids and replay rather than `MemoryViewStore`.
 *
 * Two engines of the same owner are two tabs; an engine of another owner is
 * a colleague. The orders behind the views are the example server's, in a
 * tenant of this run's own that holds none: what is held to account here is
 * where the views are and at which revision, not what they show.
 */

import type { FetchExchange, ResponseInterceptor } from '@ahoo-wang/fetcher';
import {
  DEFAULT_RUNTIME_LIMITS,
  defaultRuntimeEnvironment,
  isViewWriteError,
  ViewEngine,
  type RecordViewConfig,
  type ViewRuntime,
  type ViewWriteError,
} from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';
import { describe, expect, it } from 'vitest';
import {
  ORDERS,
  freshTenant,
  orderSource,
  ordersDefinition,
} from '../view-engine/salesOrders';
import {
  actingAs,
  type ViewStoreServer,
  viewStoreServers,
} from './viewStoreServer';

const run = Date.now();
const APP = 'engine-e2e';

const config: RecordViewConfig = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [{ field: 'state.totalAmount', direction: 'DESC' }],
  pageSize: 5,
  layout: 'table',
  table: { columns: [{ field: 'aggregateId' }, { field: 'state.status' }] },
  card: { title: 'aggregateId', fields: [] },
};

/**
 * Drops the answer to the next write matching `matches` after the server
 * took it: the request landed, the caller never hears so — an unknown
 * outcome, as a connection reset on the way back makes one.
 */
class DropAnswerOnce implements ResponseInterceptor {
  readonly name = 'DropAnswerOnce';
  readonly order = Number.MIN_SAFE_INTEGER;
  dropped = 0;
  private armed = false;
  constructor(
    private readonly matches: (method: string, url: string) => boolean,
  ) {}

  arm(): void {
    this.armed = true;
  }

  intercept(exchange: FetchExchange): void {
    const { method = 'GET', url } = exchange.request;
    if (this.armed && this.matches(method, url)) {
      this.armed = false;
      this.dropped += 1;
      throw new TypeError('The connection was reset.');
    }
  }
}

interface Tab {
  engine: ViewEngine;
  drop: DropAnswerOnce;
}

/** One engine — a tab — of `owner` in `tenantId`, on `server`. */
function tabOf(server: ViewStoreServer, tenantId: string, owner: string): Tab {
  const fetcher = actingAs({ tenantId, owner, appId: APP }, server);
  const drop = new DropAnswerOnce(
    (method, url) => method === 'PUT' && url.endsWith('/save'),
  );
  fetcher.interceptors.response.use(drop);
  const engine = new ViewEngine({
    resources: [
      { definition: ordersDefinition, source: orderSource(freshTenant('e2e')) },
    ],
    store: new WowViewStore({ fetcher }),
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: defaultRuntimeEnvironment({ timeZone: 'UTC' }),
  });
  return { engine, drop };
}

async function failedWrite(write: Promise<unknown>): Promise<ViewWriteError> {
  const thrown = await write.catch((error: unknown) => error);
  if (!isViewWriteError(thrown))
    throw new Error(`Not a write failure: ${String(thrown)}`);
  return thrown;
}

function pageSizeOf(runtime: ViewRuntime): number {
  return (runtime.getSnapshot().draft as RecordViewConfig).pageSize;
}

describe.each(viewStoreServers.map(server => [server.name, server] as const))(
  'the view engine on WowViewStore, on %s',
  (_, server) => {
    const tenantId = `engine-${run}-${server.url.replace(/\W/g, '')}`;

    it('saves, saves again and saves as, each at the revision the server keeps', async () => {
      const { engine } = tabOf(server, tenantId, 'alice');
      const runtime = engine.create(ORDERS, {
        title: 'Largest orders',
        scope: 'personal',
        config,
      });

      const created = await engine.save(runtime);
      expect(created).toMatchObject({
        title: 'Largest orders',
        scope: 'personal',
        revision: '1',
      });

      runtime.edit({ pageSize: 10 });
      const saved = await engine.save(runtime);
      expect(saved).toMatchObject({ id: created.id, revision: '2' });
      expect(runtime.getSnapshot().dirty).toBe(false);

      const copy = await engine.saveAs(runtime, {
        title: 'Largest orders, shared',
        scope: 'shared',
      });
      expect(copy).toMatchObject({ scope: 'shared', revision: '1' });
      expect(copy.id).not.toBe(created.id);

      // A fresh tab reads both from the server, as they were saved.
      const { engine: reader } = tabOf(server, tenantId, 'alice');
      const listed = (await reader.list(ORDERS)).items.filter(
        ({ scope }) => scope !== 'system',
      );
      expect(
        listed.map(({ id, scope, revision }) => ({ id, scope, revision })),
      ).toEqual(
        expect.arrayContaining([
          { id: created.id, scope: 'personal', revision: '2' },
          { id: copy.id, scope: 'shared', revision: '1' },
        ]),
      );
      expect(pageSizeOf(await reader.open(copy.id))).toBe(10);
    });

    it('reloads or overwrites after a conflict with another tab', async () => {
      const first = tabOf(server, tenantId, 'bob').engine;
      const second = tabOf(server, tenantId, 'bob').engine;
      const view = await first.save(
        first.create(ORDERS, { title: 'Two tabs', scope: 'personal', config }),
      );
      const mine = await first.open(view.id);
      const theirs = await second.open(view.id);

      mine.edit({ pageSize: 20 });
      await first.save(mine);

      // Reload: the stale tab takes what the server holds, and its draft goes.
      theirs.edit({ pageSize: 7 });
      const conflict = await failedWrite(second.save(theirs));
      expect(conflict.state.kind).toBe('conflict');
      const reloaded = await second.resolveConflict(theirs, 'reload');
      expect(reloaded).toMatchObject({ revision: '2' });
      expect(pageSizeOf(theirs)).toBe(20);

      // Overwrite: the stale tab's intent, as a new write at the revision
      // the conflict reported.
      mine.edit({ pageSize: 30 });
      await first.save(mine);
      theirs.edit({ pageSize: 8 });
      await failedWrite(second.save(theirs));
      const overwritten = await second.resolveConflict(theirs, 'overwrite');
      expect(overwritten).toMatchObject({ revision: '4' });
      const { engine: reader } = tabOf(server, tenantId, 'bob');
      expect(pageSizeOf(await reader.open(view.id))).toBe(8);
    });

    it('retries an unknown outcome under its request id, and it lands once', async () => {
      const { engine, drop } = tabOf(server, tenantId, 'carol');
      const view = await engine.save(
        engine.create(ORDERS, {
          title: 'Flaky line',
          scope: 'personal',
          config,
        }),
      );
      const runtime = await engine.open(view.id);
      runtime.edit({ pageSize: 15 });

      drop.arm();
      const unknown = await failedWrite(engine.save(runtime));
      expect(drop.dropped).toBe(1);
      expect(unknown.state.kind).toBe('unknown');

      // The first attempt landed; the retry answers it rather than writing
      // a second time.
      const retried = await engine.retryWrite(runtime);
      expect(retried).toMatchObject({ id: view.id, revision: '2' });
      expect(engine.pendingWrites().size).toBe(0);
      const { engine: reader } = tabOf(server, tenantId, 'carol');
      expect(
        (await reader.list(ORDERS)).items.find(({ id }) => id === view.id),
      ).toMatchObject({ revision: '2' });
    });

    it('sets a personal view shared, in place, for a colleague to open', async () => {
      const { engine } = tabOf(server, tenantId, 'dave');
      const view = await engine.save(
        engine.create(ORDERS, {
          title: 'For the team',
          scope: 'personal',
          config,
        }),
      );
      const { engine: colleague } = tabOf(server, tenantId, 'erin');
      expect(
        (await colleague.list(ORDERS)).items.map(({ id }) => id),
      ).not.toContain(view.id);

      const shared = await engine.changeAudience(view.id, 'shared');
      expect(shared).toMatchObject({ id: view.id, scope: 'shared' });

      const listed = (await colleague.list(ORDERS)).items.find(
        ({ id }) => id === view.id,
      );
      expect(listed).toMatchObject({ scope: 'shared', title: 'For the team' });
      expect(pageSizeOf(await colleague.open(view.id))).toBe(5);
    });
  },
);
