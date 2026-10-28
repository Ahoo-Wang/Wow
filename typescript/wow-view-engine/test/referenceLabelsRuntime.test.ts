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
 * Reference ids read as names through a real engine (D354): one resolve per
 * remote per page for the ids it does not know, one cache for every view and
 * panel, invalidated by the host, called off with the view, and its failure
 * told to the host.
 */

import type { FilterPagedQuery } from '@ahoo-wang/wow-client/dsl';
import {
  AggregationGroupType,
  FilterOperator,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  builtinFieldKinds,
  drillGroups,
  isExportCancelledError,
  type RecordViewRuntime,
  type DashboardPanel,
  type DataViewDefinition,
  type FieldOption,
  type FilterTree,
  type OptionSource,
  type RecordData,
  type RuntimeLimits,
  type ViewErrorEvent,
  type ViewInstance,
} from '../src/index.js';
import { DashboardViewRuntime } from '../src/runtime/dashboardRuntime.js';
import { memorySource } from '../src/testing/index.js';
import { dealsDefinition, kanbanConfig } from './fixtures/kanban.js';
import {
  analysisConfig,
  dashboardConfig,
  nextTask,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  resourcesOf,
  testEnvironment,
  testSource,
} from './fixtures.js';

/** Orders whose customer and seller are ids of two remotes. */
function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      {
        name: 'customer',
        label: 'Customer',
        kind: 'reference',
        remote: 'customers',
      },
      { name: 'seller', label: 'Seller', kind: 'reference', remote: 'people' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'customer',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
      ],
    },
  });
}

/** Page one and page two; page one names `c1` twice. */
const PAGES: Record<number, RecordData[]> = {
  1: [
    { id: 'o-1', customer: 'c1', seller: 'p1', amount: 1 },
    { id: 'o-2', customer: 'c2', seller: 'p1', amount: 2 },
    { id: 'o-3', customer: 'c1', seller: 'p2', amount: 3 },
  ],
  2: [
    { id: 'o-4', customer: 'c2', seller: 'p2', amount: 4 },
    { id: 'o-5', customer: 'c3', seller: 'p3', amount: 5 },
  ],
};

const config = () =>
  recordConfig({
    pageSize: 3,
    table: { columns: [{ field: 'id' }, { field: 'customer' }] },
    card: { title: 'id', fields: ['amount'] },
  });

/** A remote that names every id it is asked, keeping what each ask held. */
function remote(
  answer: (
    ids: (string | number)[],
    signal?: AbortSignal,
  ) => Promise<FieldOption[]> = ids =>
    Promise.resolve(ids.map(id => ({ value: id, label: `Name ${id}` }))),
) {
  const asked: (string | number)[][] = [];
  const signals: (AbortSignal | undefined)[] = [];
  const source: OptionSource = {
    search: () => Promise.resolve({ items: [], nextCursor: null }),
    resolve: (ids, { signal } = {}) => {
      asked.push([...ids]);
      signals.push(signal);
      return answer(ids, signal);
    },
  };
  return { source, asked, signals };
}

function engineWith(
  options: {
    customers?: OptionSource;
    people?: OptionSource;
    instances?: ViewInstance[];
    limits?: Partial<RuntimeLimits>;
    rows?: (page: number, size: number) => RecordData[];
  } = {},
) {
  const onError = vi.fn<(event: ViewErrorEvent) => void>();
  const customers = options.customers ?? remote().source;
  const people = options.people ?? remote().source;
  const rows = options.rows ?? ((page: number) => PAGES[page] ?? []);
  const source = testSource({
    paged: vi.fn((query: FilterPagedQuery) => {
      const { index = 1, size = 20 } = query.pagination ?? {};
      const list = rows(index, size);
      return Promise.resolve({ total: 5, exact: true, list });
    }),
    aggregate: vi.fn(() =>
      Promise.resolve([
        { customer: 'c1', orders: 2 },
        { customer: 'c9', orders: 1 },
      ]),
    ),
  });
  const store = new MemoryViewStore({ instances: options.instances ?? [] });
  const engine = new ViewEngine({
    resources: resourcesOf([definition(), overviewDefinition()], () => source),
    store,
    resolveOptions: key => (key === 'customers' ? customers : people),
    environment: { ...testEnvironment().environment, onError },
    ...(options.limits ? { limits: options.limits } : {}),
  });
  return { engine, onError, source, store };
}

/** A record view of the orders; one created runs at once. */
function recordView(engine: ViewEngine): RecordViewRuntime {
  return engine.create('orders', {
    title: 'Scratch',
    scope: 'personal',
    config: config(),
  }) as RecordViewRuntime;
}

describe('a record view reads its page’s ids as names', () => {
  it('asks once per remote per page, and the next page only its new ids', async () => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    const runtime = recordView(engine);
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2']]);
    expect(runtime.labels.text('customers', 'c1')).toBe('Name c1');

    runtime.page({ index: 2 });
    await nextTask();
    expect(customers.asked).toEqual([['c1', 'c2'], ['c3']]);
  });

  it('shares what one view learnt with the next', async () => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    recordView(engine);
    await nextTask();
    const other = recordView(engine);
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2']]);
    expect(other.labels.text('customers', 'c2')).toBe('Name c2');
  });

  it('asks again after the host invalidates', async () => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    const runtime = recordView(engine);
    await nextTask();

    engine.invalidateLabels('customers', ['c2']);
    expect(runtime.labels.text('customers', 'c2')).toBe('c2');
    runtime.refresh();
    await nextTask();
    engine.invalidateLabels();
    runtime.refresh();
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2'], ['c2'], ['c1', 'c2']]);
  });

  it('calls the read off when the view is closed', async () => {
    const customers = remote(() => new Promise(() => {}));
    const { engine } = engineWith({ customers: customers.source });
    const runtime = recordView(engine);
    await nextTask();
    expect(customers.signals[0]?.aborted).toBe(false);

    engine.close(runtime);

    expect(customers.signals[0]?.aborted).toBe(true);
  });

  it('tells the host of a failed read, and shows the ids', async () => {
    const boom = new Error('customers are down');
    const customers = remote(() => Promise.reject(boom));
    const { engine, onError } = engineWith({ customers: customers.source });
    const runtime = recordView(engine);
    await nextTask();

    expect(onError).toHaveBeenCalledExactlyOnceWith({
      kind: 'query',
      error: boom,
      context: { operation: 'labels', remote: 'customers' },
    });
    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(runtime.labels.text('customers', 'c1')).toBe('c1');
  });
});

describe('an analysis reads its group keys as names', () => {
  it('asks the keys of a group on a reference field', async () => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    const runtime = engine.create('orders', {
      title: 'By customer',
      scope: 'personal',
      config: analysisConfig({
        groups: [
          {
            alias: 'customer',
            field: 'customer',
            type: AggregationGroupType.TERMS,
          },
        ],
        chart: {
          type: 'bar',
          cartesian: { x: 'customer', series: [{ metric: 'orders' }] },
        },
      }),
    });
    await nextTask();

    expect(runtime.getSnapshot().query.status).toBe('success');
    expect(customers.asked).toEqual([['c1', 'c9']]);
    expect(runtime.labels.text('customers', 'c9')).toBe('Name c9');
  });
});

describe('a board’s panels read one cache', () => {
  it('asks the ids two panels show once between them', async () => {
    const instance = (id: string): ViewInstance => ({
      id,
      definitionId: 'orders',
      title: id,
      scope: 'shared',
      revision: 'r1',
      config: config(),
    });
    const panel = (id: string, y: number) =>
      ({
        id,
        kind: 'view',
        instanceId: id,
        bindings: [],
        layout: { x: 0, y, w: 12, h: 4 },
      }) as DashboardPanel;
    const customers = remote();
    const { engine, store } = engineWith({
      customers: customers.source,
      instances: [instance('first'), instance('second')],
    });
    const board = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({
          panels: [panel('first', 0), panel('second', 4)],
        }),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(board.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');

    expect(customers.asked).toEqual([['c1', 'c2']]);
    expect(runtime.labels.text('customers', 'c1')).toBe('Name c1');
  });
});

describe('rows read beside a page ask their ids too', () => {
  it('asks only the new ids of a kanban lane’s next page', async () => {
    const owners = remote();
    const deals = dealsDefinition();
    const definition = dealsDefinition({
      fields: [
        ...deals.fields,
        { name: 'owner', label: 'Owner', kind: 'reference', remote: 'users' },
      ],
    });
    const rows = [
      { id: 'd-1', stage: 'LEAD', owner: 'u1' },
      { id: 'd-2', stage: 'LEAD', owner: 'u2' },
      { id: 'd-3', stage: 'LEAD', owner: 'u3' },
      { id: 'd-4', stage: 'WON', owner: 'u1' },
    ];
    const engine = new ViewEngine({
      resources: resourcesOf([definition], () => memorySource(rows)),
      store: new MemoryViewStore(),
      resolveOptions: () => owners.source,
    });
    const runtime = engine.create('deals', {
      title: 'Pipeline',
      scope: 'personal',
      config: kanbanConfig({
        pageSize: 2,
        table: { columns: [{ field: 'id' }, { field: 'owner' }] },
      }),
    }) as RecordViewRuntime;
    await vi.waitFor(() => expect(owners.asked).toHaveLength(1));
    expect([...(owners.asked[0] ?? [])].sort()).toEqual(['u1', 'u2']);

    const lead = runtime.getSnapshot().result?.data;
    if (lead?.kind !== 'record') throw new Error('expected a kanban');
    runtime.loadLane(lead.view.lanes![0]!.id);
    await vi.waitFor(() => expect(owners.asked).toHaveLength(2));
    expect(owners.asked[1]).toEqual(['u3']);
    runtime.dispose();
  });

  it('asks every reference field of a record read whole', async () => {
    const customers = remote();
    const people = remote();
    const { engine } = engineWith({
      customers: customers.source,
      people: people.source,
      // The page is empty; the record is read on its own.
      rows: (_page, size) =>
        size === 1 ? [{ id: 'o-9', customer: 'c7', seller: 'p7' }] : [],
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: config(),
    }) as RecordViewRuntime;
    await nextTask();

    // No column shows the seller; the detail does.
    const record = await runtime.fetchRecord('o-9');
    await nextTask();

    expect(record).toMatchObject({ id: 'o-9' });
    expect(customers.asked).toEqual([['c7']]);
    expect(people.asked).toEqual([['p7']]);
  });
});

/** An "all of" with one condition on `field` holding `items`. */
const holding = (
  field: string,
  ...items: { id: string; label: string }[]
): FilterTree => ({
  op: 'and',
  children: [{ field, operator: FilterOperator.IN, value: { items } }],
});

describe('a view reads its conditions’ bare ids with its page’s', () => {
  const filtered = (filter: FilterTree, scopeFilter?: FilterTree) => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: { ...config(), filter },
      ...(scopeFilter ? { scopeFilter } : {}),
    });
    return customers;
  };

  it('asks an id its condition holds alone in the page’s one request', async () => {
    const customers = filtered(holding('customer', { id: 'c9', label: 'c9' }));
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2', 'c9']]);
  });

  it('asks nothing for a condition that holds its name already', async () => {
    const customers = filtered(holding('customer', { id: 'c9', label: 'Ann' }));
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2']]);
  });

  it('asks the ids of the host’s scope too', async () => {
    const customers = filtered(
      { op: 'and', children: [] },
      holding('customer', { id: 'c8', label: 'c8' }),
    );
    await nextTask();

    expect(customers.asked).toEqual([['c1', 'c2', 'c8']]);
  });
});

describe('a board reads its filters’ bare ids', () => {
  it('asks the ids its filter’s default and its fixed scope hold', async () => {
    const customers = remote();
    const { engine, store } = engineWith({ customers: customers.source });
    const board = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({
          fixed: holding('buyer', { id: 'c8', label: 'c8' }),
          fields: [
            {
              name: 'buyer',
              label: 'Buyer',
              kind: 'reference',
              remote: 'customers',
              default: { items: [{ id: 'c7', label: 'c7' }] },
            },
          ],
        }),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(board.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');

    // No panel shows them; the board asks them itself, in one request.
    expect(customers.asked).toEqual([['c8', 'c7']]);
    expect(runtime.labels.text('customers', 'c7')).toBe('Name c7');
  });
});

describe('an export reads its file’s ids as names first', () => {
  /** Five orders, each of its own customer, all on the export's one page. */
  const five = (page: number, size: number): RecordData[] =>
    page === 1 && size > 3
      ? [1, 2, 3, 4, 5].map(n => ({ id: `o-${n}`, customer: `c${n}` }))
      : [];

  it('asks in requests of `referenceBatch`, `referenceConcurrency` at a time, before it hands the rows over', async () => {
    let open = 0;
    let most = 0;
    const customers = remote(async ids => {
      open += 1;
      most = Math.max(most, open);
      await nextTask();
      open -= 1;
      return ids.map(id => ({ value: id, label: `Name ${id}` }));
    });
    const people = remote();
    const { engine } = engineWith({
      customers: customers.source,
      people: people.source,
      limits: { referenceBatch: 2, referenceConcurrency: 2 },
      rows: five,
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: config(),
    }) as RecordViewRuntime;

    const exported = await runtime.exportRows();

    expect(exported.rows).toHaveLength(5);
    // Each id once, in the order the file holds them, two requests at most.
    expect(customers.asked).toEqual([['c1', 'c2'], ['c3', 'c4'], ['c5']]);
    expect(most).toBe(2);
    // The file's columns are the table's: no column shows the seller.
    expect(people.asked).toEqual([]);
    expect(runtime.labels.text('customers', 'c5')).toBe('Name c5');
  });

  it('asks only what the screen has not', async () => {
    const customers = remote();
    const { engine } = engineWith({ customers: customers.source });
    const runtime = recordView(engine);
    await nextTask();

    await runtime.exportRows();

    // Page one is the whole export, and its ids are known.
    expect(customers.asked).toEqual([['c1', 'c2']]);
  });

  it('writes the ids when the read fails, and still hands the rows over', async () => {
    const customers = remote(() => Promise.reject(new Error('down')));
    const { engine, onError } = engineWith({
      customers: customers.source,
      rows: five,
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: config(),
    }) as RecordViewRuntime;

    const exported = await runtime.exportRows();

    expect(exported.rows).toHaveLength(5);
    expect(runtime.labels.text('customers', 'c1')).toBe('c1');
    expect(onError).toHaveBeenCalledWith(
      expect.objectContaining({
        context: { operation: 'labels', remote: 'customers' },
      }),
    );
    expect(onError).not.toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'export' }),
    );
  });

  it('is cancelled by its signal while the names are out', async () => {
    const customers = remote(
      (_ids, signal) =>
        new Promise((_resolve, reject) =>
          signal?.addEventListener('abort', () => reject(signal.reason)),
        ),
    );
    const { engine, onError } = engineWith({
      customers: customers.source,
      rows: five,
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: config(),
    }) as RecordViewRuntime;
    const controller = new AbortController();

    const exported = runtime.exportRows({ signal: controller.signal });
    await vi.waitFor(() => expect(customers.asked).toHaveLength(1));
    controller.abort();

    const error = await exported.catch((caught: unknown) => caught);
    expect(isExportCancelledError(error)).toBe(true);
    expect(customers.signals[0]?.aborted).toBe(true);
    expect(onError).not.toHaveBeenCalled();
  });
});

describe('a press writes the name beside the id', () => {
  const byCustomer = () =>
    analysisConfig({
      groups: [
        {
          alias: 'customer',
          field: 'customer',
          type: AggregationGroupType.TERMS,
        },
      ],
      chart: {
        type: 'bar',
        cartesian: { x: 'customer', series: [{ metric: 'orders' }] },
      },
    });

  it('names a drilled reference by `labelOf`, and by its id without one', () => {
    const fields = definition().fields;
    const row = { customer: 'c1', orders: 2 };
    const named = drillGroups(byCustomer(), fields, builtinFieldKinds, row, {
      timeZone: 'UTC',
      labelOf: (remote, id) => `${remote}:${id}`,
    });
    const bare = drillGroups(byCustomer(), fields, builtinFieldKinds, row, {
      timeZone: 'UTC',
    });

    expect(named?.[0]?.conditions[0]?.value).toEqual({
      items: [{ id: 'c1', label: 'customers:c1' }],
    });
    expect(bare?.[0]?.conditions[0]?.value).toEqual({
      items: [{ id: 'c1', label: 'c1' }],
    });
  });

  it('sets a board filter on ids with the name the panel reads', async () => {
    const customers = remote();
    const chart: ViewInstance = {
      id: 'by-customer',
      definitionId: 'orders',
      title: 'By customer',
      scope: 'shared',
      revision: 'r1',
      config: byCustomer(),
    };
    const { engine, store } = engineWith({
      customers: customers.source,
      instances: [chart],
    });
    const board = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({
          fields: [
            {
              name: 'buyer',
              label: 'Buyer',
              kind: 'reference',
              remote: 'customers',
            },
          ],
          panels: [
            {
              id: 'chart',
              kind: 'view',
              instanceId: 'by-customer',
              bindings: [{ globalField: 'buyer', panelField: 'customer' }],
              click: { kind: 'filter', filter: 'buyer' },
              layout: { x: 0, y: 0, w: 12, h: 4 },
            } as DashboardPanel,
          ],
        }),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(board.id);
    await nextTask();
    if (!(runtime instanceof DashboardViewRuntime))
      throw new Error('expected a dashboard');
    expect(customers.asked).toEqual([['c1', 'c9']]);

    expect(runtime.crossFilter('chart', { customer: 'c1' }).kind).toBe('set');
    expect(runtime.getSnapshot().filters.values.buyer).toEqual({
      items: [{ id: 'c1', label: 'Name c1' }],
    });
  });
});
