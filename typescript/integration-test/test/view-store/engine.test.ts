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
 * The view engine on the store, end to end (view-store-backend.md 7
 * 「引擎端到端」, 10): the engine's own commands — save, save as, a
 * conflict reloaded or overwritten, an unknown outcome retried, setting a
 * view shared and taking it back, preferences — against the real view
 * store service, and the engine's queries through the query routes
 * (`viewSourceOf`).
 *
 * The data is the service's own: a definition over the `view` aggregate's
 * snapshots, which a user reads as their own saved views. So what a record
 * view and an analysis answer is added up from the views the test saved,
 * read through `POST /view-store/view/snapshot/query` as any snapshot
 * model is.
 */

import type { FetchExchange, ResponseInterceptor } from '@ahoo-wang/fetcher';
import {
  AggregationFunction,
  AggregationGroupType,
  SortDirection,
  SearchMode,
  AggregationMetricType,
} from '@ahoo-wang/wow-client/dsl';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  defaultRuntimeEnvironment,
  isViewWriteError,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordViewConfig,
  type RecordViewRuntime,
  type ViewRuntime,
  type ViewWriteError,
} from '@ahoo-wang/wow-view-engine';
import { viewSourceOf, WowViewStore } from '@ahoo-wang/wow-view-store';
import { describe, expect, it } from 'vitest';
import { nextResult } from './nextResult';
import { actingAs, SHARED_WRITER } from './viewStoreServer';

const { TERMS } = AggregationGroupType;
const { SUM, MAX } = AggregationFunction;

const tenantId = `engine-${Date.now()}`;
const APP = 'engine-e2e';
const VIEWS = 'saved-views';

/** The caller's saved views, as the service's own snapshots. */
const viewsDefinition: DataViewDefinition = {
  id: VIEWS,
  title: 'Saved views',
  recordNoun: 'view',
  kind: 'data',
  source: VIEWS,
  fields: [
    { name: 'aggregateId', label: 'View', kind: 'string', sortable: true },
    { name: 'state.title', label: 'Title', kind: 'string', sortable: true },
    {
      name: 'state.kind',
      label: 'Kind',
      kind: 'enum',
      sortable: true,
      options: ['record', 'analysis', 'dashboard'].map(value => ({
        value,
        label: value,
      })),
    },
    { name: 'version', label: 'Version', kind: 'number', sortable: true },
  ],
  record: { rowKey: 'aggregateId', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'state.kind', groups: [TERMS], functions: [] },
      { field: 'version', groups: [], functions: [SUM, MAX] },
    ],
  },
};

const config: RecordViewConfig = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [{ field: 'state.title', direction: SortDirection.ASC }],
  pageSize: 5,
  layout: 'table',
  table: { columns: [{ field: 'state.title' }, { field: 'state.kind' }] },
  card: { title: 'state.title', fields: [] },
};

/**
 * Drops the answer to the next write matching `matches` after the service
 * took it: the request landed, the caller never hears so.
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

/** One engine — a tab — of `user`, its views and its data on the service. */
function tabOf(user: string, roles: readonly string[] = []): Tab {
  const fetcher = actingAs({ tenantId, user, appId: APP, roles });
  const drop = new DropAnswerOnce(
    (method, url) => method === 'POST' && url.endsWith('/save-view'),
  );
  fetcher.interceptors.response.use(drop);
  const engine = new ViewEngine({
    resources: [
      {
        definition: viewsDefinition,
        source: viewSourceOf(
          new SnapshotQueryClient(
            { context: 'view-store', name: 'view' },
            { fetcher },
          ),
        ),
      },
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

describe('the view engine on the store', () => {
  it('saves, saves again and saves as, each at the revision the service keeps', async () => {
    const { engine } = tabOf('alice', [SHARED_WRITER]);
    const runtime = engine.create(VIEWS, {
      title: 'Largest views',
      scope: 'personal',
      config,
    });

    const created = await engine.save(runtime);
    expect(created).toMatchObject({
      title: 'Largest views',
      scope: 'personal',
      revision: '1',
    });

    runtime.edit({ pageSize: 10 });
    const saved = await engine.save(runtime);
    expect(saved).toMatchObject({ id: created.id, revision: '2' });
    expect(runtime.getSnapshot().dirty).toBe(false);

    const copy = await engine.saveAs(runtime, {
      title: 'Largest views, shared',
      scope: 'shared',
    });
    expect(copy).toMatchObject({ scope: 'shared', revision: '1' });
    expect(copy.id).not.toBe(created.id);

    const { engine: reader } = tabOf('alice');
    const listed = (await reader.list(VIEWS)).items.filter(
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
    const first = tabOf('bob').engine;
    const second = tabOf('bob').engine;
    const view = await first.save(
      first.create(VIEWS, { title: 'Two tabs', scope: 'personal', config }),
    );
    const mine = await first.open(view.id);
    const theirs = await second.open(view.id);

    mine.edit({ pageSize: 20 });
    await first.save(mine);

    theirs.edit({ pageSize: 7 });
    const conflict = await failedWrite(second.save(theirs));
    expect(conflict.state.kind).toBe('conflict');
    const reloaded = await second.resolveConflict(theirs, 'reload');
    expect(reloaded).toMatchObject({ revision: '2' });
    expect(pageSizeOf(theirs)).toBe(20);

    mine.edit({ pageSize: 30 });
    await first.save(mine);
    theirs.edit({ pageSize: 8 });
    await failedWrite(second.save(theirs));
    const overwritten = await second.resolveConflict(theirs, 'overwrite');
    expect(overwritten).toMatchObject({ revision: '4' });
    expect(pageSizeOf(await tabOf('bob').engine.open(view.id))).toBe(8);
  });

  it('retries an unknown outcome under its request id, and it lands once', async () => {
    const { engine, drop } = tabOf('carol');
    const view = await engine.save(
      engine.create(VIEWS, { title: 'Flaky line', scope: 'personal', config }),
    );
    const runtime = await engine.open(view.id);
    runtime.edit({ pageSize: 15 });

    drop.arm();
    const unknown = await failedWrite(engine.save(runtime));
    expect(drop.dropped).toBe(1);
    expect(unknown.state.kind).toBe('unknown');

    // The first attempt landed; the service answers the retry as its
    // duplicate rather than writing a second time.
    const retried = await engine.retryWrite(runtime);
    expect(retried).toMatchObject({ id: view.id, revision: '2' });
    expect(engine.pendingWrites().size).toBe(0);
    expect(
      (await tabOf('carol').engine.list(VIEWS)).items.find(
        ({ id }) => id === view.id,
      ),
    ).toMatchObject({ revision: '2' });
  });

  it('sets a personal view shared for a colleague, who takes it back as theirs', async () => {
    const { engine } = tabOf('dave');
    const view = await engine.save(
      engine.create(VIEWS, {
        title: 'For the team',
        scope: 'personal',
        config,
      }),
    );
    const { engine: colleague } = tabOf('erin', [SHARED_WRITER]);
    expect(
      (await colleague.list(VIEWS)).items.map(({ id }) => id),
    ).not.toContain(view.id);

    const shared = await engine.changeAudience(view.id, 'shared');
    expect(shared).toMatchObject({ id: view.id, scope: 'shared' });
    expect(
      (await colleague.list(VIEWS)).items.find(({ id }) => id === view.id),
    ).toMatchObject({ scope: 'shared', title: 'For the team' });

    // Without the shared role, dave may not take it back: the service says so.
    await expect(engine.changeAudience(view.id, 'personal')).rejects.toThrow();

    const claimed = await colleague.changeAudience(view.id, 'personal');
    expect(claimed).toMatchObject({ id: view.id, scope: 'personal' });
    expect((await engine.list(VIEWS)).items.map(({ id }) => id)).not.toContain(
      view.id,
    );
    expect(pageSizeOf(await colleague.open(view.id))).toBe(5);
  });

  it('keeps the default view and the order in the preferences', async () => {
    const { engine } = tabOf('frank');
    const one = await engine.save(
      engine.create(VIEWS, { title: 'One', scope: 'personal', config }),
    );
    const two = await engine.save(
      engine.create(VIEWS, { title: 'Two', scope: 'personal', config }),
    );
    await engine.reorder(VIEWS, [two.id, one.id]);
    const written = await engine.setDefault(VIEWS, two.id);
    expect(written).toMatchObject({
      order: [two.id, one.id],
      defaultInstanceId: two.id,
      revision: '2',
    });

    // Another tab reads them back from the service.
    const reader = tabOf('frank').engine;
    const preferences = await reader.preferences(VIEWS);
    expect(preferences).toEqual(written);
    const { items } = await reader.list(VIEWS);
    expect(reader.resolveDefault(items, preferences)).toBe(two.id);
  });

  it('runs a record view and an analysis through the query routes', async () => {
    const { engine } = tabOf('grace');
    for (const title of ['Alpha', 'Beta', 'Gamma'])
      await engine.save(
        engine.create(VIEWS, { title, scope: 'personal', config }),
      );

    const records = engine.create(VIEWS, {
      title: 'Mine',
      scope: 'personal',
      config,
    }) as RecordViewRuntime;
    const state = await nextResult(records);
    const data = state.result?.data;
    if (data?.kind !== 'record') throw new Error('expected a record result');
    // Sorted by title, ascending, on the service.
    expect(data.view.rows.map(row => row.data.state)).toEqual([
      expect.objectContaining({ title: 'Alpha' }),
      expect.objectContaining({ title: 'Beta' }),
      expect.objectContaining({ title: 'Gamma' }),
    ]);
    records.dispose();

    const analysis: AnalysisViewConfig = {
      kind: 'analysis',
      filter: { op: 'and', children: [] },
      filterMode: 'simple',
      refresh: { interval: null },
      sort: [],
      limit: 100,
      layout: 'table',
      table: { columns: [] },
      groups: [{ type: SearchMode.TERMS, field: 'state.kind', alias: 'kind' }],
      metrics: [{ type: AggregationMetricType.COUNT, alias: 'views' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'kind', series: [{ metric: 'views' }] },
      },
    };
    const runtime = engine.create(VIEWS, {
      title: 'By kind',
      scope: 'personal',
      config: analysis,
    }) as ViewRuntime<AnalysisViewConfig>;
    const analysed = await nextResult(runtime);
    const result = analysed.result?.data;
    if (result?.kind !== 'analysis') throw new Error('expected an analysis');
    expect(result.view.rows.map(row => [row.kind, row.views])).toEqual([
      ['record', 3],
    ]);
    runtime.dispose();
  });
});
