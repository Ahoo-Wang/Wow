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

import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  emptyDashboardConfig,
  systemInstanceId,
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DashboardViewConfig,
  type FilterNode,
  type RecordData,
  type RuntimeEnvironment,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import {
  ACTIVE,
  EXECUTION_FAILED,
  executionFailedDefinition,
} from './compensation.js';
import { rowSource } from './rowSource.js';

/**
 * The host application's home page: one dashboard over the compensation
 * service's failed executions, the same data the two consoles beside it
 * read. The page is the host's; the dashboard is a view like any other, so
 * everything on it is a saved view of `execution-failed` — one of them the
 * console's own system view — and the dashboard only places them.
 */

/** The dashboard's definition: dashboards own no data, only a catalogue entry. */
export const homeDefinition: DashboardDefinition = {
  id: 'home',
  title: '首页',
  kind: 'dashboard',
};

/** What the home page embeds. */
export const HOME_DASHBOARD = 'home-operations';

const ACTIVE_ONLY: FilterNode = {
  field: 'state.status',
  operator: 'IN',
  value: ACTIVE,
};

/** A count of the executions `filter` keeps, read as one number. */
function countCard(filter: FilterNode[]): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [],
    metrics: [{ alias: 'count', type: 'COUNT' }],
    sort: [],
    limit: 1,
    layout: 'chart',
    table: { columns: [] },
    chart: { type: 'metric', metric: { metric: 'count' } },
  };
}

/**
 * The views the dashboard places, shared by the operations team. They are
 * saved views rather than more system views of `execution-failed`, so the
 * console's own list stays what the console is for; the status distribution
 * is the console's system view as it is.
 */
export const homeViews: ViewInstance[] = [
  // The board itself is the operations team's, saved and shared rather than
  // shipped with the code: the home page embeds it in the interactive tier,
  // a report read and never built there (D36) — the team rearranges it in
  // `DashboardWorkbench`.
  {
    id: HOME_DASHBOARD,
    definitionId: 'home',
    title: '运营概览',
    scope: 'shared',
    revision: '1',
    config: homeDashboard(),
  },
  shared('home-active', '活动失败', countCard([ACTIVE_ONLY])),
  shared(
    'home-unrecoverable',
    '活动失败中不可恢复',
    countCard([
      ACTIVE_ONLY,
      {
        field: 'state.recoverable',
        operator: 'IN',
        value: ['UNRECOVERABLE'],
      },
    ]),
  ),
  shared(
    'home-today',
    '今日新增失败',
    countCard([
      {
        field: 'firstEventTime',
        operator: 'BETWEEN',
        value: { type: 'preset', preset: 'today' },
      },
    ]),
  ),
  // This month, not the last fourteen days: a calendar period cuts on day
  // boundaries, where a rolling window would start halfway into its first
  // day and draw that day's bar as a dip that never happened.
  shared('home-daily', '本月每日新增失败', {
    kind: 'analysis',
    filter: {
      op: 'and',
      children: [
        {
          field: 'firstEventTime',
          operator: 'BETWEEN',
          value: { type: 'preset', preset: 'thisMonth' },
        },
      ],
    },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [
      {
        type: 'DATE_HISTOGRAM',
        field: 'firstEventTime',
        alias: 'day',
        unit: 'DAY',
      },
    ],
    metrics: [{ alias: 'count', type: 'COUNT' }],
    // The month reads oldest first; the chart would run forward either way.
    sort: [{ alias: 'day', direction: 'ASC' }],
    limit: 31,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'day', series: [{ metric: 'count' }] },
      legend: 'none',
    },
  }),
  shared('home-processors', '活动失败最多的处理器', {
    kind: 'analysis',
    filter: { op: 'and', children: [ACTIVE_ONLY] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [
      {
        type: 'TERMS',
        field: 'state.function.processorName',
        alias: 'processor',
      },
    ],
    metrics: [{ alias: 'count', type: 'COUNT' }],
    sort: [{ alias: 'count', direction: 'DESC' }],
    limit: 6,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: {
        x: 'processor',
        series: [{ metric: 'count' }],
        orientation: 'horizontal',
      },
      legend: 'none',
    },
  }),
  shared('home-recent', '最近的活动失败', {
    kind: 'record',
    filter: { op: 'and', children: [ACTIVE_ONLY] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'eventTime', direction: 'DESC' }],
    pageSize: 10,
    layout: 'table',
    summaries: [],
    // What an operator glancing at the home page needs to know which flow
    // broke and how; the identifiers are one click away, in the console.
    table: {
      columns: [
        'state.function.processorName',
        'state.status',
        'state.error.errorCode',
        'state.retryState.retries',
        'eventTime',
      ].map(field => ({ field })),
    },
    card: {
      title: 'state.function.processorName',
      fields: ['state.status', 'state.error.errorCode', 'eventTime'],
    },
  }),
];

function shared(
  id: string,
  title: string,
  config: ViewInstance['config'],
): ViewInstance {
  return {
    id,
    definitionId: EXECUTION_FAILED,
    title,
    scope: 'shared',
    revision: '1',
    config,
  };
}

/**
 * Three numbers across the top, the month's trend and the distribution
 * under them, and the failures themselves last. No global filter: the home
 * page answers one question for everyone, and narrowing it is what the
 * consoles are for.
 */
function homeDashboard(): DashboardViewConfig {
  const panel = (
    id: string,
    instanceId: string,
    title: string,
    layout: { x: number; y: number; w: number; h: number },
  ) => ({ id, kind: 'view' as const, title, instanceId, bindings: [], layout });
  return {
    ...emptyDashboardConfig(),
    panels: [
      panel('active', 'home-active', '活动失败', { x: 0, y: 0, w: 8, h: 1 }),
      panel('unrecoverable', 'home-unrecoverable', '其中不可恢复', {
        x: 8,
        y: 0,
        w: 8,
        h: 1,
      }),
      panel('today', 'home-today', '今日新增', { x: 16, y: 0, w: 8, h: 1 }),
      panel('daily', 'home-daily', '本月每日新增失败', {
        x: 0,
        y: 1,
        w: 16,
        h: 4,
      }),
      panel(
        'by-status',
        systemInstanceId(EXECUTION_FAILED, 'by-status'),
        '按状态分布',
        { x: 16, y: 1, w: 8, h: 4 },
      ),
      panel('recent', 'home-recent', '最近的活动失败', {
        x: 0,
        y: 5,
        w: 14,
        h: 5,
      }),
      panel('processors', 'home-processors', '活动失败最多的处理器', {
        x: 14,
        y: 5,
        w: 10,
        h: 5,
      }),
    ],
  };
}

/**
 * An engine for the home page over `source`: the compensation definition,
 * the home dashboard, and the team's shared views in a fresh store.
 */
export function createHomeEngine(
  source: ViewSource,
  environment?: RuntimeEnvironment,
): ViewEngine {
  return new ViewEngine({
    definitions: [executionFailedDefinition, homeDefinition],
    // As the consoles: the service pages at most 100 rows at a time.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({ instances: homeViews }),
    resolveSource: () => source,
    ...(environment ? { environment } : {}),
  });
}

/* --------------------------------------------------------------------------
 * The fixture: failed executions from the start of August to a fixed
 * morning, answered in memory by `rowSource`.
 *
 * "Today" and "this month" are the runtime's clock, so the fixture pins the
 * clock and the zone the dashboard reads them in: every mount, on every
 * machine, shows the same month and the same buckets, and the regression
 * twin asserts numbers rather than shapes. Each execution is decided by its
 * index alone, so the data is as fixed as a hand-written table.
 * ------------------------------------------------------------------------ */

/** The morning the fixture's home page is opened on. */
export const HOME_FIXTURE_NOW = Date.parse('2026-09-22T10:00:00+08:00');

/** The zone its days are cut in, whatever zone the browser is in. */
export const HOME_FIXTURE_ZONE = 'Asia/Shanghai';

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;

/** Neutral names, one clearly ahead of the rest, as a real service has. */
const PROCESSORS = [
  'OrderSaga',
  'OrderSaga',
  'PaymentSaga',
  'OrderSaga',
  'InventorySaga',
  'PaymentSaga',
  'ShipmentSaga',
  'OrderSaga',
  'InvoiceSaga',
  'NotificationProcessor',
];

const ERRORS = [
  { errorCode: 'BadRequest', errorMsg: 'Inventory refused.' },
  { errorCode: 'Timeout', errorMsg: 'Payment gateway timed out.' },
  { errorCode: 'IllegalState', errorMsg: 'Order already closed.' },
];

/**
 * 52 days back from the fixture's morning, a few executions a day — more
 * mid-week than at the weekend — each opened at some hour of its day.
 */
export const HOME_FIXTURE_EXECUTIONS: RecordData[] = Array.from(
  { length: 52 },
  (_, back) => back,
)
  .flatMap(back => {
    // Midnight of that day: the morning is ten o'clock in the fixture's zone.
    const midnight = HOME_FIXTURE_NOW - 10 * HOUR_MS - back * DAY_MS;
    // This morning has had four already; other days one to five.
    const count =
      back === 0 ? 4 : 1 + ((back * 5) % 4) + (back % 7 < 2 ? 0 : 1);
    return Array.from({ length: count }, (_, slot) => {
      // Today's executions all opened before ten; earlier days' across the
      // day, never past its end.
      const hour = back === 0 ? 1 + slot * 2 : 1 + slot * 4 + (back % 3);
      return midnight + hour * HOUR_MS;
    });
  })
  .map((openedAt, index) => fixtureExecution(index, openedAt));

function fixtureExecution(index: number, openedAt: number): RecordData {
  const id = `EF-${String(index + 1).padStart(3, '0')}`;
  const status =
    index % 7 === 3 ? 'SUCCEEDED' : index % 5 === 1 ? 'PREPARED' : 'FAILED';
  const recoverable =
    index % 4 === 0
      ? 'UNRECOVERABLE'
      : index % 4 === 1
        ? 'RECOVERABLE'
        : 'UNKNOWN';
  const retries = index % 4;
  const eventTime = openedAt + (retries + 1) * 20 * 60_000;
  return {
    aggregateId: id,
    firstEventTime: openedAt,
    eventTime,
    state: {
      id,
      status,
      recoverable,
      isRetryable: status !== 'SUCCEEDED',
      isBelowRetryThreshold: retries < 3,
      function: {
        contextName: 'order-service',
        processorName: PROCESSORS[index % PROCESSORS.length],
        name: 'onOrderCreated',
        functionKind: 'EVENT',
      },
      eventId: {
        id: `${id}-event`,
        version: 1,
        aggregateId: {
          contextName: 'order-service',
          aggregateName: 'order',
          aggregateId: `order-${id}`,
        },
      },
      error: ERRORS[index % ERRORS.length],
      retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
      retryState: {
        retries,
        retryAt: eventTime,
        nextRetryAt: eventTime + 180_000,
        timeoutAt: eventTime + 120_000,
      },
    },
  };
}

/** The home page's engine over the fixture, on the fixture's morning. */
export function createHomeFixtureEngine(): ViewEngine {
  return createHomeEngine(
    rowSource(HOME_FIXTURE_EXECUTIONS),
    defaultRuntimeEnvironment({
      now: () => new Date(HOME_FIXTURE_NOW),
      timeZone: HOME_FIXTURE_ZONE,
    }),
  );
}
