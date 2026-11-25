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
 * Round 1 of the deep review: the view engine's data path (runtime, store,
 * record, filter, model). Each test states the behaviour the engine
 * promises and fails on `main` because of the defect it names.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  AggregationFunction,
  DeletionState,
  FilterOperator,
  type CursorPage,
  type CursorQuery,
  type FilterPagedQuery,
} from '@ahoo-wang/wow-client/dsl';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type RecordData,
  type RecordLaneView,
  type RecordViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { RecordDataViewRuntime } from '../src/runtime/recordRuntime.js';
import { RequestRunner } from '../src/runtime/requestRunner.js';
import { recordSource } from '../src/runtime/source.js';
import { pageSummaries, projectSummaries } from '../src/record/summaries.js';
import { memorySource } from '../src/testing/index.js';
import {
  deferred,
  ordersDefinition,
  recordConfig,
  resourcesOf,
  savedInstance,
  testEnvironment,
  testLabels,
  testSource,
} from './fixtures.js';
import { DEALS, dealsDefinition, dealsKanban } from './fixtures/kanban.js';

/** As many orders as asked for, `o-1` … `o-n`. */
function orders(count: number): RecordData[] {
  return Array.from({ length: count }, (_row, index) => ({
    id: `o-${index + 1}`,
    amount: (index + 1) * 10,
  }));
}

/** A paged source over `rows` that counts them exactly, as Wow does. */
function pagedSource(rows: RecordData[]): ViewSource {
  return testSource({
    paged: vi.fn((query: FilterPagedQuery) => {
      const { index, size } = query.pagination ?? { index: 1, size: 20 };
      const start = (index - 1) * size;
      return Promise.resolve({
        total: rows.length,
        exact: true,
        list: rows.slice(start, start + size),
      });
    }),
  });
}

function openRecord(source: ViewSource): RecordViewRuntime {
  return new RecordDataViewRuntime({
    id: 'runtime-1',
    definition: ordersDefinition(),
    config: recordConfig({ pageSize: 2 }),
    title: 'Mine',
    scope: 'personal',
    kinds: builtinFieldKinds,
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 3 },
    environment: testEnvironment().environment,
    source: recordSource(source),
    runner: new RequestRunner(),
    labels: testLabels(),
  }) as unknown as RecordViewRuntime;
}

describe('r1: an export at its ceiling', () => {
  // `ExportedRows.capped`: "True when the ceiling stopped it while the
  // source still had rows" (src/runtime/exportRows.ts). The export window
  // then says 「文件只含前 {max} 条（共 {total} 条匹配）」 (decisions.md, the
  // export window; runtime.md「导出」).
  it('r1 an export that fetched every matching row is not capped, even when their count is the ceiling', async () => {
    const source = pagedSource(orders(6));
    const runtime = openRecord(source);

    const exported = await runtime.exportRows({ max: 6 });

    expect(exported.rows).toHaveLength(6);
    expect(exported.total).toBe(6);
    expect(exported.capped).toBe(false);
    runtime.dispose();
  });
});

describe('r1: a selection made while another page is on its way', () => {
  // runtime.md「规则」: "选择绑定当前结果" — the selection only covers the
  // current result (`ViewRuntimeState.selection`: 只覆盖当前结果), and a
  // bulk action acts only on rows still in it.
  it('r1 a selection made on the rows on screen while a page turns never outlives them into the next page', async () => {
    const rows = orders(4);
    const second = deferred<{
      total: number;
      exact: boolean;
      list: RecordData[];
    }>();
    const source = testSource({
      paged: vi.fn((query: FilterPagedQuery) =>
        query.pagination?.index === 2
          ? second.promise
          : Promise.resolve({ total: 4, exact: true, list: rows.slice(0, 2) }),
      ),
    });
    const runtime = openRecord(source);
    runtime.apply();
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    runtime.page({ index: 2 });
    // The first page is still drawn while the second is asked for.
    runtime.select(['o-1']);
    expect(runtime.getSnapshot().selection).toEqual(['o-1']);
    second.resolve({ total: 4, exact: true, list: rows.slice(2, 4) });
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    const onScreen = runtime.getSnapshot().result?.data;
    const keys =
      onScreen?.kind === 'record' ? onScreen.view.rows.map(row => row.key) : [];
    expect(keys).toEqual(['o-3', 'o-4']);
    expect(
      runtime.getSnapshot().selection.filter(key => !keys.includes(key)),
    ).toEqual([]);
    runtime.dispose();
  });
});

describe('r1: a refresh on a cursor the source no longer honours', () => {
  // capabilities.md (#3502): on INVALID_CURSOR "记录视图回到第一页再问一次";
  // runtime.md「规则」: "refresh 后按新结果的行键求交集，消失的行自动移出".
  it('r1 a refresh that falls back to the first page lets go of selected rows that are not on it', async () => {
    const rows: RecordData[] = [
      { id: 'd-1', amount: 1 },
      { id: 'd-2', amount: 2 },
      { id: 'd-3', amount: 3 },
      { id: 'd-4', amount: 4 },
    ];
    let expired = false;
    const source = testSource({
      cursor: vi.fn((query: CursorQuery) => {
        if (query.cursor === 'c2' && expired)
          return Promise.reject(new Error('Invalid cursor.'));
        return Promise.resolve(
          query.cursor === 'c2'
            ? { list: rows.slice(2, 4), nextCursor: null }
            : { list: rows.slice(0, 2), nextCursor: 'c2' },
        );
      }),
    });
    const runtime = new RecordDataViewRuntime({
      id: 'runtime-cursor',
      definition: dealsDefinition(),
      config: recordConfig({ pageSize: 2 }),
      title: 'Deals',
      scope: 'personal',
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: testEnvironment().environment,
      source: recordSource(source),
      runner: new RequestRunner(),
      labels: testLabels(),
    }) as unknown as RecordViewRuntime;
    const keysOnScreen = () => {
      const data = runtime.getSnapshot().result?.data;
      return data?.kind === 'record' ? data.view.rows.map(row => row.key) : [];
    };
    runtime.apply();
    await vi.waitFor(() => expect(keysOnScreen()).toEqual(['d-1', 'd-2']));
    runtime.page({ cursor: 'c2' });
    await vi.waitFor(() => expect(keysOnScreen()).toEqual(['d-3', 'd-4']));
    runtime.select(['d-3']);

    expired = true;
    runtime.refresh();
    await vi.waitFor(() => expect(keysOnScreen()).toEqual(['d-1', 'd-2']));
    expect(runtime.getSnapshot().query.status).toBe('success');

    expect(runtime.getSnapshot().selection).toEqual([]);
    runtime.dispose();
  });
});

describe('r1: MemoryViewStore read a page at a time', () => {
  // ViewStore.list: "A page at a time … The order holds across pages", and
  // the list is "the definition's views this user sees". A delete between
  // two pages (another tab of a `localStorageSnapshot`, or this one) must
  // not hide a view that is still there.
  it('r1 a view deleted between two pages of a list never hides one that is still there', async () => {
    const instances = ['v-1', 'v-2', 'v-3', 'v-4'].map(id =>
      savedInstance({ id, title: id, scope: 'personal', revision: '1' }),
    );
    const store = new MemoryViewStore({ instances, pageSize: 2 });

    const first = await store.list('orders');
    expect(first.items.map(item => item.id)).toEqual(['v-1', 'v-2']);
    await store.delete('v-1', '1', { requestId: 'delete-v-1' });
    const second = await store.list('orders', {
      cursor: first.nextCursor ?? '',
    });

    const listed = [...first.items, ...second.items].map(item => item.id);
    expect(listed).toEqual(expect.arrayContaining(['v-2', 'v-3', 'v-4']));
  });
});

describe('r1: a record read whole from a view of deleted records', () => {
  // runtime.md「取一条」: fetchRecord reads one record by its row key under
  // the injected scope, without the page's conditions; ui/record.md「记录详情」:
  // a row on the page opens and is completed by that read, "不在了说
  // 「这条记录已不在了」". A Wow source answers only the records not deleted
  // unless the query says otherwise (D17-2, src/testing/filter.ts), so the
  // deletion state the page ran under is what makes the record exist at all.
  it('r1 a deleted record a view of deleted records shows is read whole, not answered as gone', async () => {
    const definition = ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string', sortable: true },
        { name: '@deleted', label: 'Deleted', kind: 'deletion' },
        { name: 'amount', label: 'Amount', kind: 'number' },
      ],
      analysis: undefined,
      views: [],
    });
    const source = memorySource([
      { id: 'o-1', amount: 10, deleted: true },
      { id: 'o-2', amount: 20 },
    ]);
    const runtime = new RecordDataViewRuntime({
      id: 'runtime-bin',
      definition,
      config: recordConfig({
        filter: {
          op: 'and',
          children: [
            {
              field: '@deleted',
              operator: FilterOperator.DELETION,
              value: DeletionState.DELETED,
            },
          ],
        },
      }),
      title: 'Recycle bin',
      scope: 'personal',
      kinds: builtinFieldKinds,
      limits: DEFAULT_RUNTIME_LIMITS,
      environment: testEnvironment().environment,
      source: recordSource(source),
      runner: new RequestRunner(),
      labels: testLabels(),
    }) as unknown as RecordViewRuntime;
    runtime.apply();
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    const data = runtime.getSnapshot().result?.data;
    expect(
      data?.kind === 'record' ? data.view.rows.map(row => row.key) : [],
    ).toEqual(['o-1']);

    const whole = await runtime.fetchRecord('o-1');

    expect(whole).toMatchObject({ id: 'o-1', deleted: true });
    runtime.dispose();
  });
});

describe('r1: a remembered tab beside the reader’s own preference commands', () => {
  // ViewEngine.rememberTab: "A convenience: it never rejects and never
  // leaves an outcome behind" — the reader never sees it. Yet it is written
  // through the same ledger key as the reader's own commands
  // (`preferences:<definitionId>`, writeLedger.ts `writeKey`), so while it
  // is on its way a 「设为默认」 is refused as `view.write.in-flight`.
  it('r1 setting the default view while a remembered tab is being written lands, and keeps both', async () => {
    const store = new MemoryViewStore();
    const keep = store.setPreferences.bind(store);
    const gate = deferred<void>();
    let held = false;
    store.setPreferences = async (definitionId, preferences, context) => {
      if (!held) {
        held = true;
        await gate.promise;
      }
      return keep(definitionId, preferences, context);
    };
    const engine = new ViewEngine({
      resources: resourcesOf([ordersDefinition()], () => testSource()),
      store,
    });

    const remembered = engine.rememberTab('orders', 'board-1', 'tab-2');
    await vi.waitFor(() => expect(held).toBe(true));
    const defaulted = engine.setDefault('orders', 'all');
    // Settled either way before the gate opens, so a refusal is not hidden
    // behind the write it collided with.
    const outcome = defaulted.then(
      preferences => ({ landed: preferences.defaultInstanceId }),
      (error: unknown) => ({ refused: String(error) }),
    );
    gate.resolve();
    await remembered;

    expect(await outcome).toEqual({ landed: 'all' });
    const stored = await store.getPreferences('orders');
    expect(stored).toMatchObject({
      defaultInstanceId: 'all',
      lastTabs: { 'board-1': 'tab-2' },
    });
  });
});

describe('r1: summaries over numbers the wire gives as text', () => {
  // `@ahoo-wang/wow-client` reads an int64 or a decimal field of a record as
  // its exact text (TEXT_NUMBER_FORMATS in wow-generator: int64, bigint,
  // decimal): a Kotlin `Long` or `BigDecimal` amount arrives as "10".
  const rows: RecordData[] = [
    { id: 'o-1', amount: '10.50' },
    { id: 'o-2', amount: '20.25' },
  ];
  const config = recordConfig({
    summaries: [{ field: 'amount', fn: AggregationFunction.SUM }],
  });

  it('r1 a page summary adds up the amounts the wire gave as exact text', () => {
    const row = projectSummaries(ordersDefinition(), config, {
      scope: 'page',
      rows,
    });

    expect(row.cells[0]?.value).toBe(30.75);
  });

  it('r1 the page row beside a total adds up amounts given as exact text', () => {
    const total = projectSummaries(ordersDefinition(), config, {
      scope: 'total',
      result: [{ amount_sum: 100 }],
    });
    const page = pageSummaries(
      total.cells,
      rows.map(data => ({ key: data.id as string, data })),
    );

    expect(page.cells[0]?.value).toBe(30.75);
  });
});

describe('r1: a kanban lane loading more while the board is redrawn', () => {
  async function openKanban(source: ViewSource) {
    const engine = new ViewEngine({
      resources: resourcesOf([dealsDefinition()], () => source),
      store: new MemoryViewStore({ instances: [dealsKanban] }),
    });
    const runtime = (await engine.open(dealsKanban.id)) as RecordViewRuntime;
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    return runtime;
  }

  function lanesOf(runtime: RecordViewRuntime): RecordLaneView[] {
    const data = runtime.getSnapshot().result?.data;
    return data?.kind === 'record' ? (data.view.lanes ?? []) : [];
  }

  // runtime.md「规则」: an apply that only redraws re-projects the rows on
  // screen and `applied` becomes the draft; `result.own` is the config the
  // rows answer (viewRuntimeTypes.ts), which `useStale` and the next
  // redraw or `loadLane` read (`result.own !== applied` refuses both).
  it('r1 a lane page that lands after a redraw keeps the redraw and answers the applied config', async () => {
    const memory = memorySource(DEALS);
    const held = deferred<CursorPage<RecordData>>();
    let holding = false;
    const source: ViewSource = {
      ...memory,
      cursor: (query: CursorQuery, options) =>
        holding ? held.promise : memory.cursor(query, options),
    };
    const runtime = await openKanban(source);
    const lead = lanesOf(runtime)[0]!;

    holding = true;
    runtime.loadLane(lead.id);
    // A column's width is only how the rows are drawn: no query (restyle).
    runtime.edit({
      table: { columns: [{ field: 'id', width: 240 }, { field: 'amount' }] },
    });
    runtime.apply();
    const redrawn = runtime.getSnapshot();
    expect(redrawn.result?.own).toBe(redrawn.applied);

    held.resolve({
      list: [{ id: 'd-3', stage: 'LEAD', amount: 30 }],
      nextCursor: null,
    });
    await vi.waitFor(() => expect(lanesOf(runtime)[0]?.rows).toHaveLength(3));

    const after = runtime.getSnapshot();
    expect(after.result?.own).toEqual(after.applied);
    runtime.dispose();
  });
});
