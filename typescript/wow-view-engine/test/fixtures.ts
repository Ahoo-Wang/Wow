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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
} from '@ahoo-wang/fetcher-wow';
import { vi } from 'vitest';
import { analysisScope } from '../src/index.js';
import type {
  AnalysisViewConfig,
  DashboardDefinition,
  DashboardViewConfig,
  DataViewDefinition,
  PanelReference,
  RecordViewConfig,
  RuntimeEnvironment,
  ViewConfig,
  ViewInstance,
  ViewSource,
} from '../src/index.js';

export const NOW = new Date('2026-09-16T10:30:00.000Z');

export function ordersDefinition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'id', label: 'Order', kind: 'string' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'status', label: 'Status', kind: 'string' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        summary: ['SUM'],
        sortable: true,
      },
    ],
    record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
    views: [
      {
        id: 'all',
        title: 'All orders',
        config: recordConfig(),
      },
    ],
    ...overrides,
  };
}

/** 09:21:55 UTC on 18 Sep 2026, as Wow keeps a time: epoch milliseconds. */
export const INSTANT = 1789723315014;

/**
 * Kathmandu runs forty-five minutes off the hour, so no machine's own zone
 * passes for it: a time that reads as it does there was shown in the zone it
 * was given.
 */
export const ZONE = 'Asia/Kathmandu';

/** A datetime as a surface in `en-GB` on {@link ZONE}'s time shows it. */
export function inZone(value: number): string {
  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'medium',
    timeZone: ZONE,
  }).format(value);
}

/**
 * The orders capability with values only their field can make readable: a
 * warehouse enum whose codes have names, and a creation time kept as epoch
 * milliseconds that analyses can bucket by month.
 */
export function namedOrdersDefinition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields.map(field =>
        field.name === 'warehouse'
          ? {
              ...field,
              kind: 'enum',
              options: [{ value: 'CN', label: 'China' }],
            }
          : field,
      ),
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.MONTH],
        },
      ],
    },
  });
}

export function recordConfig(
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'record',
    sort: [],
    pageSize: 20,
    layout: 'table',
    table: { columns: [{ field: 'id' }, { field: 'amount' }] },
    card: { title: 'id', fields: ['amount'] },
    ...overrides,
  };
}

/**
 * The view a suite opens: one saved, personal record view of `orders`. The
 * hook suites and the UI suites both start from it, so it lives here rather
 * than once in each.
 */
export const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

export function analysisConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
    metrics: [
      {
        alias: 'orders',
        type: `${AggregationMetricType.COUNT}`,
      },
    ],
    sort: [],
    limit: 100,
    layout: 'table',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    },
    ...overrides,
  };
}

export function overviewDefinition(
  overrides: Partial<DashboardDefinition> = {},
): DashboardDefinition {
  return {
    id: 'overview',
    title: 'Overview',
    kind: 'dashboard',
    ...overrides,
  };
}

export function dashboardConfig(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'dashboard',
    fields: [],
    panels: [],
    ...overrides,
  };
}

/** A saved record instance, the usual target of a dashboard panel. */
export function savedInstance(
  overrides: Partial<ViewInstance> = {},
): ViewInstance {
  return {
    id: 'pending',
    definitionId: 'orders',
    title: 'Pending orders',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
    ...overrides,
  };
}

/** What `validateDashboard` is given for one referenced instance. */
export function panelReference(
  instance: Partial<ViewInstance> = {},
  definition: Partial<DataViewDefinition> = {},
): PanelReference {
  const found = savedInstance(instance);
  const owner = ordersDefinition(definition);
  return {
    instance: found,
    definition: owner,
    fields:
      found.config.kind === 'analysis' && owner.analysis
        ? [
            ...analysisScope(
              owner,
              owner.analysis,
              found.config,
            ).fields.values(),
          ]
        : owner.fields,
  };
}

/** Narrows an open view's config to the record kind, or fails the test. */
export function requireRecordConfig(config: ViewConfig): RecordViewConfig {
  if (config.kind !== 'record')
    throw new Error(`expected a record config, got ${config.kind}`);
  return config;
}

export const ROWS = [
  { id: 'o-1', warehouse: 'CN', amount: 10, status: 'PENDING' },
  { id: 'o-2', warehouse: 'CN', amount: 20, status: 'SHIPPED' },
];

/** A `ViewSource` whose three methods are spies with sensible defaults. */
export function testSource(overrides: Partial<ViewSource> = {}): ViewSource {
  return {
    paged: vi.fn(() => Promise.resolve({ total: 2, list: [...ROWS] })),
    cursor: vi.fn(() =>
      Promise.resolve({ nextCursor: 'cursor-2', list: [...ROWS] }),
    ),
    aggregate: vi.fn(() =>
      Promise.resolve([{ warehouse: 'CN', orders: 2, amount_sum: 30 }]),
    ),
    ...overrides,
  };
}

export interface TestEnvironment {
  environment: RuntimeEnvironment;
  /** Fires every timer whose delay has elapsed, oldest first. */
  advance(ms: number): void;
  setVisible(visible: boolean): void;
  readonly timers: number;
}

/** Time, timers and visibility under the test's control rather than the host's. */
export function testEnvironment(start: Date = NOW): TestEnvironment {
  let clock = start.getTime();
  let visible = true;
  const listeners = new Set<() => void>();
  const pending = new Map<number, { due: number; callback: () => void }>();
  let handle = 0;

  const environment: RuntimeEnvironment = {
    now: () => new Date(clock),
    timeZone: 'UTC',
    setTimeout: (callback, ms) => {
      handle += 1;
      pending.set(handle, { due: clock + ms, callback });
      return handle;
    },
    clearTimeout: id => {
      pending.delete(id as number);
    },
    visibility: {
      isVisible: () => visible,
      subscribe: listener => {
        listeners.add(listener);
        return () => listeners.delete(listener);
      },
    },
  };

  return {
    environment,
    advance(ms) {
      clock += ms;
      for (const [id, timer] of [...pending]) {
        if (timer.due > clock) continue;
        pending.delete(id);
        timer.callback();
      }
    },
    setVisible(next) {
      visible = next;
      for (const listener of [...listeners]) listener();
    },
    get timers() {
      return pending.size;
    },
  };
}

/** A promise a test settles by hand, for superseding and queueing. */
export function deferred<T>(): {
  promise: Promise<T>;
  resolve(value: T): void;
  reject(reason: unknown): void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/**
 * Queries that see only what is drawn.
 *
 * Every chart renders an `sr-only` table of the same numbers beside it, so a
 * plain text query finds each value twice; these assertions are about the
 * drawing. What the reading says is pinned in
 * `test/analysisChartA11y.test.tsx`.
 */
export const DRAWN = {
  ignore: 'script, style, [data-slot="chart-reading"] *',
} as const;
