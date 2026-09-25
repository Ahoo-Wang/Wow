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
 * The example server's sales orders, as the view engine sees them: a
 * definition written from `GET /sales-order/snapshot/schema`, an engine whose
 * source is the Wow snapshot query client itself, and a set of orders seeded
 * through commands into a tenant of the suite's own — so every number a test
 * asserts is added up from `SEEDS` and the snapshots the server wrote, never
 * read off the engine's own output.
 */

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  CommandHeaders,
  CommandStage,
  SnapshotQueryClient,
  filter,
} from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type DashboardDefinition,
  type DataViewDefinition,
  type RuntimeEnvironment,
  type RuntimeLimits,
  type ViewConfig,
  type ViewRuntime,
  type ViewRuntimeState,
} from '@ahoo-wang/wow-view-engine';
import { exampleServerURL } from '../../src/wow/exampleFetcher';

export const ORDERS = 'sales-orders';
export const BOARD = 'sales-board';

/** How long one command may take to reach its snapshot on a warm server. */
const COMMAND_TIMEOUT_MS = 30_000;

/** One order the suite writes: where it goes, what it holds, what is paid. */
export interface OrderSeed {
  key: string;
  province: string;
  city: string;
  detail: string;
  items: { productId: string; quantity: number }[];
  /** Paid in full (`PAID`), in part (still `CREATED`), or not at all. */
  paid?: 'full' | number;
}

/** The example prices every product at 10 and refuses another price. */
export const PRICE = 10;

/**
 * Thirteen orders over four provinces and twelve cities — more cities than
 * a chart has colours, so a split by city folds into 「其他」 — each total a
 * different amount, so every sort and every ranking has one answer.
 */
export const SEEDS: readonly OrderSeed[] = [
  s('o01', 'Zhejiang', 'Hangzhou', 'West Lake Road 1', [['p-a', 3]], 'full'),
  s('o02', 'Zhejiang', 'Ningbo', 'Harbor Road 2', [['p-b', 5]]),
  s(
    'o03',
    'Zhejiang',
    'Wenzhou',
    'Pine Lane 3',
    [
      ['p-a', 2],
      ['p-c', 4],
    ],
    25,
  ),
  s('o04', 'Jiangsu', 'Nanjing', 'Bell Road 4', [['p-d', 1]], 'full'),
  s('o05', 'Jiangsu', 'Suzhou', 'Garden Lane 5', [['p-e', 7]]),
  s('o06', 'Jiangsu', 'Wuxi', 'Lake Road 6', [['p-a', 8]], 40),
  s('o07', 'Jiangsu', 'Suzhou', 'Garden Lane 7', [['p-f', 9]], 'full'),
  s('o08', 'Shanghai', 'Shanghai', 'Bund Road 8', [
    ['p-b', 2],
    ['p-g', 2],
  ]),
  s('o09', 'Zhejiang', 'Shaoxing', 'Canal Road 9', [['p-h', 11]]),
  s('o10', 'Jiangsu', 'Changzhou', 'Dragon Road 10', [['p-c', 12]], 'full'),
  s('o11', 'Zhejiang', 'Jiaxing', 'South Lake Lane 11', [['p-i', 13]]),
  s('o12', 'Jiangsu', 'Yangzhou', 'Slender Lane 12', [['p-j', 14]]),
  s('o13', 'Anhui', 'Hefei', 'Chaohu Road 13', [
    ['p-a', 5],
    ['p-k', 10],
  ]),
];

function s(
  key: string,
  province: string,
  city: string,
  detail: string,
  items: [string, number][],
  paid?: 'full' | number,
): OrderSeed {
  return {
    key,
    province,
    city,
    detail,
    items: items.map(([productId, quantity]) => ({ productId, quantity })),
    ...(paid === undefined ? {} : { paid }),
  };
}

export function totalOf(seed: OrderSeed): number {
  return seed.items.reduce((sum, item) => sum + item.quantity * PRICE, 0);
}

export function paidOf(seed: OrderSeed): number {
  if (seed.paid === 'full') return totalOf(seed);
  return seed.paid ?? 0;
}

export function statusOf(seed: OrderSeed): 'PAID' | 'CREATED' {
  return seed.paid === 'full' ? 'PAID' : 'CREATED';
}

/** A seeded order as the server stored it: its id and when it was created. */
export interface SeededOrder extends OrderSeed {
  id: string;
  total: number;
  /** `firstEventTime`, in epoch milliseconds, as the snapshot holds it. */
  createdAt: number;
}

/** A tenant nobody else writes to, so the suite reads only its own orders. */
export function freshTenant(label: string): string {
  return `ve-${label}-${Date.now().toString(36)}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

/** The `ownerId` every seeded order is written under. */
const OWNER = 'view-engine-it';

async function command(
  path: string,
  body: unknown,
): Promise<{ aggregateId: string }> {
  const response = await fetch(new URL(path, exampleServerURL), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
      // The order aggregate is spaced; every seeded order shares one space.
      [CommandHeaders.SPACE_ID]: OWNER,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(COMMAND_TIMEOUT_MS),
  });
  const result = (await response.json()) as {
    aggregateId: string;
    errorCode: string;
    errorMsg?: string;
  };
  if (!response.ok || result.errorCode !== 'Ok')
    throw new Error(
      `${path} answered HTTP ${response.status}: ${JSON.stringify(result)}`,
    );
  return result;
}

/** Writes one order, and pays it as its seed says. */
async function writeOrder(tenant: string, seed: OrderSeed): Promise<string> {
  const { aggregateId } = await command(
    `tenant/${tenant}/owner/${OWNER}/sales-order`,
    {
      items: seed.items.map(item => ({ ...item, price: PRICE })),
      address: {
        country: 'China',
        province: seed.province,
        city: seed.city,
        district: seed.city,
        detail: seed.detail,
      },
      fromCart: false,
    },
  );
  const paid = paidOf(seed);
  if (paid > 0)
    await command(`tenant/${tenant}/sales-order/${aggregateId}/pay`, {
      paymentId: `${seed.key}-payment`,
      amount: paid,
    });
  return aggregateId;
}

/** The server's own snapshot client for a tenant's orders: the engine's source. */
export function orderSource(tenant: string): SnapshotQueryClient<unknown> {
  return new SnapshotQueryClient({
    basePath: `tenant/${tenant}/sales-order`,
    fetcher: new Fetcher({ baseURL: exampleServerURL }),
  });
}

/**
 * Seeds `seeds` into `tenant`, in order and one at a time, then reads back
 * when the server says each was created. `pauseAfter` names seeds after
 * which the seeding waits that long, so their creation times fall into
 * different buckets of a histogram.
 */
export async function seedOrders(
  tenant: string,
  seeds: readonly OrderSeed[] = SEEDS,
  pauseAfter: Readonly<Record<string, number>> = {},
): Promise<SeededOrder[]> {
  const ids: string[] = [];
  for (const seed of seeds) {
    ids.push(await writeOrder(tenant, seed));
    const pause = pauseAfter[seed.key];
    if (pause) await new Promise(resolve => setTimeout(resolve, pause));
  }
  // Read straight off the server, not through the engine: this is the
  // ground truth the engine's answers are held to.
  const page = await orderSource(tenant).paged({
    filter: filter.matchAll(),
    pagination: { index: 1, size: seeds.length },
  });
  const created = new Map(
    (page.list as { aggregateId: string; firstEventTime: number }[]).map(
      row => [row.aggregateId, row.firstEventTime],
    ),
  );
  return seeds.map((seed, index) => {
    const id = ids[index];
    const createdAt = created.get(id);
    if (createdAt === undefined)
      throw new Error(`Seeded order ${seed.key} (${id}) is not in ${tenant}.`);
    return { ...seed, id, total: totalOf(seed), createdAt };
  });
}

const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { SECOND, DAY, WEEK, MONTH } = AggregationDateUnit;

/**
 * The orders, written by hand from the server's snapshot schema: each field
 * with the operators, sorting and aggregation its capabilities admit. The
 * search field is here because a list page declares one; the example's
 * MongoDB backend has no full-text capability, and the suite checks that
 * the refusal reaches the view as the server said it.
 */
export const ordersDefinition: DataViewDefinition = {
  id: ORDERS,
  title: 'Sales orders',
  recordNoun: 'order',
  kind: 'data',
  source: ORDERS,
  fields: [
    { name: 'aggregateId', label: 'Order', kind: 'string', sortable: true },
    {
      name: 'state.status',
      label: 'Status',
      kind: 'enum',
      sortable: true,
      options: [
        { value: 'CREATED', label: 'Created' },
        { value: 'PAID', label: 'Paid' },
        { value: 'SHIPPED', label: 'Shipped' },
        { value: 'RECEIVED', label: 'Received' },
      ],
    },
    {
      name: 'state.address.province',
      label: 'Province',
      kind: 'enum',
      sortable: true,
      options: ['Anhui', 'Jiangsu', 'Shanghai', 'Zhejiang'].map(value => ({
        value,
        label: value,
      })),
    },
    {
      name: 'state.address.city',
      label: 'City',
      kind: 'string',
      sortable: true,
    },
    { name: 'state.address.detail', label: 'Address', kind: 'string' },
    {
      name: 'state.totalAmount',
      label: 'Total',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG', 'MIN', 'MAX'],
    },
    {
      name: 'state.paidAmount',
      label: 'Paid',
      kind: 'number',
      sortable: true,
      summary: ['SUM'],
    },
    {
      name: 'firstEventTime',
      label: 'Created',
      kind: 'datetime',
      sortable: true,
      summary: ['MIN', 'MAX'],
    },
    {
      name: 'search',
      label: 'Search',
      kind: 'search',
      searchFields: ['state.address.detail'],
    },
    {
      name: 'state.items',
      label: 'Items',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      elementTitle: 'productId',
      elements: [
        { name: 'productId', label: 'Product', kind: 'string' },
        { name: 'quantity', label: 'Quantity', kind: 'number' },
      ],
    },
  ],
  record: { rowKey: 'aggregateId', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    having: true,
    expressions: true,
    fields: [
      ...['state.status', 'state.address.province', 'state.address.city'].map(
        field => ({
          field,
          groups: [TERMS],
          functions: [],
          distinctCount: true,
        }),
      ),
      ...['state.totalAmount', 'state.paidAmount'].map(field => ({
        field,
        groups: [HISTOGRAM],
        functions: [SUM, AVG, MIN, MAX],
        percentile: true,
      })),
      {
        field: 'firstEventTime',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [SECOND, DAY, WEEK, MONTH],
      },
    ],
    elements: [
      {
        path: 'state.items',
        aggregations: [
          {
            field: 'productId',
            groups: [TERMS],
            functions: [],
            distinctCount: true,
          },
          { field: 'quantity', groups: [], functions: [SUM, AVG, MAX] },
        ],
      },
    ],
  },
};

export const boardDefinition: DashboardDefinition = {
  id: BOARD,
  title: 'Sales board',
  kind: 'dashboard',
};

/**
 * Pages of five, so thirteen orders are three pages and an export pages the
 * server three times; the rest is the engine's defaults.
 */
export const LIMITS = { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 5 };

/**
 * An engine over one tenant's orders, under `limits`, in UTC — so a day, a week and a month
 * are the server's buckets and the ones this suite adds up — on the ambient
 * clock unless `now` says otherwise.
 */
export function ordersEngine(
  tenant: string,
  overrides: Partial<RuntimeEnvironment> = {},
  limits: RuntimeLimits = LIMITS,
): { engine: ViewEngine; store: MemoryViewStore } {
  const source = orderSource(tenant);
  const store = new MemoryViewStore({ instances: [] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition, boardDefinition],
    store,
    limits,
    environment: defaultRuntimeEnvironment({ timeZone: 'UTC', ...overrides }),
    resolveSource: () => source,
  });
  return { engine, store };
}

/**
 * The next answer a runtime settles on after `previous`: resolves with the
 * state once a result other than `previous` has landed, and rejects with the
 * query's issue when it fails instead.
 */
export function nextResult<C extends ViewConfig>(
  runtime: ViewRuntime<C>,
  previous: ViewRuntimeState<C>['result'] = null,
): Promise<ViewRuntimeState<C>> {
  return new Promise((resolve, reject) => {
    let stop = () => {};
    const check = () => {
      const state = runtime.getSnapshot();
      const refused = state.issues.filter(found => found.severity === 'error');
      if (state.query.status === 'error') {
        stop();
        reject(new QueryFailed(state.query.error));
      } else if (state.query.status === 'idle' && refused.length > 0) {
        // Admission refused the draft, so nothing will run.
        stop();
        reject(new QueryFailed(refused));
      } else if (
        state.query.status === 'success' &&
        state.result !== null &&
        state.result !== previous
      ) {
        stop();
        resolve(state);
      }
    };
    stop = runtime.subscribe(check);
    check();
  });
}

/** What `nextResult` rejects with: the issue the runtime reported. */
export class QueryFailed extends Error {
  constructor(readonly issue: unknown) {
    super(`The query failed: ${JSON.stringify(issue)}`);
    this.name = 'QueryFailed';
  }
}

/** Adds up `values`. */
export function sum(values: readonly number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

/** `rows` grouped by `key`, each group's orders in seed order. */
export function groupBy<K>(
  rows: readonly SeededOrder[],
  key: (row: SeededOrder) => K,
): Map<K, SeededOrder[]> {
  const groups = new Map<K, SeededOrder[]>();
  for (const row of rows) {
    const k = key(row);
    groups.set(k, [...(groups.get(k) ?? []), row]);
  }
  return groups;
}
