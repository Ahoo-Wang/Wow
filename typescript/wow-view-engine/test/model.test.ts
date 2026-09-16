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
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  BUILTIN_FIELD_KIND_IDS,
  CHART_FAMILY,
  CHART_TYPES,
  CODE_REVISION,
  DEFAULT_RUNTIME_LIMITS,
  MAX_TIMER_DELAY_MS,
  SUMMARY_FUNCTIONS,
  SYSTEM_INSTANCE_ID_PREFIX,
  SYSTEM_INSTANCE_ID_SEPARATOR,
  VIEW_KINDS,
  VIEW_SCOPES,
  type AnalysisViewConfig,
  type ChartSpec,
  type DashboardViewConfig,
  type FilterOperatorName,
  type RecordViewConfig,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
} from '../src/index.js';

const filterBase = {
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
} as const;

describe('model constants', () => {
  it('describes every view kind, scope and chart type exactly once', () => {
    expect(VIEW_KINDS).toEqual(['record', 'analysis', 'dashboard']);
    expect(VIEW_SCOPES).toEqual(['system', 'shared', 'personal']);
    expect(new Set(CHART_TYPES).size).toBe(CHART_TYPES.length);
    expect(new Set(BUILTIN_FIELD_KIND_IDS).size).toBe(
      BUILTIN_FIELD_KIND_IDS.length,
    );
    expect(SUMMARY_FUNCTIONS).toContain('SUM');
  });

  it('maps every chart type to a family sub-object of ChartSpec', () => {
    const optional: (keyof ChartSpec)[] = [
      'cartesian',
      'pie',
      'heatmap',
      'scatter',
      'funnel',
      'metric',
    ];
    for (const type of CHART_TYPES) {
      expect(optional).toContain(CHART_FAMILY[type]);
    }
    expect(CHART_FAMILY.bar).toBe('cartesian');
    expect(CHART_FAMILY.combo).toBe('cartesian');
    expect(CHART_FAMILY.funnel).toBe('funnel');
  });

  it('keeps the refresh bounds inside a 32-bit millisecond timer', () => {
    const { minRefreshInterval, maxRefreshInterval } = DEFAULT_RUNTIME_LIMITS;
    expect(minRefreshInterval).toBeGreaterThan(0);
    expect(maxRefreshInterval).toBeGreaterThan(minRefreshInterval);
    expect(maxRefreshInterval * 1000).toBeLessThan(MAX_TIMER_DELAY_MS);
  });

  it('gives every budget a positive integer default', () => {
    for (const [name, value] of Object.entries(DEFAULT_RUNTIME_LIMITS)) {
      expect(Number.isInteger(value), name).toBe(true);
      expect(value, name).toBeGreaterThan(0);
    }
  });

  it('reserves an instance id namespace for code-declared system views', () => {
    expect(SYSTEM_INSTANCE_ID_PREFIX).toBe('system');
    expect(SYSTEM_INSTANCE_ID_SEPARATOR).toBe(':');
    expect(CODE_REVISION).toBe('code');
  });
});

describe('configs stay plain JSON', () => {
  it('stores Wow enum values as string literals', () => {
    const operator: FilterOperatorName = 'EQ';
    expect(operator).toBe(FilterOperator.EQ);

    const analysis: AnalysisViewConfig = {
      ...filterBase,
      kind: 'analysis',
      refresh: { interval: null },
      groups: [
        {
          type: 'DATE_HISTOGRAM',
          field: 'createdAt',
          alias: 'month',
          unit: 'MONTH',
        },
      ],
      metrics: [{ type: 'COUNT', alias: 'orders' }],
      sort: [{ alias: 'orders', direction: 'DESC' }],
      limit: 100,
      layout: 'chart',
      table: { columns: [] },
      chart: {
        type: 'bar',
        cartesian: { x: 'month', series: [{ metric: 'orders' }] },
      },
    };

    expect(analysis.groups[0]).toMatchObject({
      unit: AggregationDateUnit.MONTH,
      type: AggregationGroupType.DATE_HISTOGRAM,
    });
    expect(JSON.parse(JSON.stringify(analysis))).toEqual(analysis);
  });

  it('keeps both record layouts and both analysis presentations', () => {
    const record: RecordViewConfig = {
      ...filterBase,
      kind: 'record',
      refresh: { interval: 30 },
      sort: [{ field: 'createdAt', direction: 'DESC' }],
      pageSize: 20,
      layout: 'table',
      table: { columns: [{ field: 'id' }, { field: 'amount', width: 120 }] },
      card: { title: 'id', fields: ['status', 'amount'] },
    };

    // Switching layout is one field; the other layout's settings survive.
    const asCard: RecordViewConfig = { ...record, layout: 'card' };
    expect(asCard.table.columns).toHaveLength(2);
    expect(asCard.card.title).toBe('id');
  });

  it('separates data panels from content panels', () => {
    const dashboard: DashboardViewConfig = {
      ...filterBase,
      kind: 'dashboard',
      refresh: { interval: null },
      fields: [{ name: 'warehouse', label: 'Warehouse', kind: 'string' }],
      panels: [
        {
          kind: 'view',
          id: 'pending',
          layout: { x: 0, y: 0, w: 6, h: 4 },
          instanceId: 'orders-pending',
          bindings: [{ globalField: 'warehouse', panelField: 'warehouse' }],
        },
        {
          kind: 'markdown',
          id: 'note',
          layout: { x: 6, y: 0, w: 6, h: 2 },
          content: '# Daily overview',
        },
      ],
    };

    const dataPanels = dashboard.panels.filter(panel => panel.kind === 'view');
    expect(dataPanels).toHaveLength(1);
    expect(dataPanels[0].bindings[0].panelField).toBe('warehouse');
  });

  it('narrows a config union by its kind', () => {
    const configs: ViewConfig[] = [
      {
        ...filterBase,
        kind: 'record',
        refresh: { interval: null },
        sort: [],
        pageSize: 20,
        layout: 'table',
        table: { columns: [] },
        card: { title: 'id', fields: [] },
      },
    ];
    for (const config of configs) {
      if (config.kind === 'record') expect(config.pageSize).toBe(20);
    }
  });
});

describe('definitions are code', () => {
  it('declares fields, capabilities and system views together', () => {
    const orders: ViewDefinition = {
      id: 'orders',
      title: 'Orders',
      kind: 'data',
      source: 'orders',
      fields: [
        { name: 'id', label: 'Order', kind: 'string' },
        { name: 'amount', label: 'Amount', kind: 'number', summary: ['SUM'] },
        {
          name: 'createdAt',
          label: 'Created',
          kind: 'datetime',
          sortable: true,
        },
      ],
      record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
      analysis: {
        count: true,
        fields: [
          {
            field: 'amount',
            groups: [AggregationGroupType.TERMS],
            functions: [AggregationFunction.SUM],
          },
        ],
      },
      views: [
        {
          id: 'all',
          title: 'All',
          config: {
            ...filterBase,
            kind: 'record',
            refresh: { interval: null },
            sort: [],
            pageSize: 20,
            layout: 'table',
            table: { columns: [{ field: 'id' }] },
            card: { title: 'id', fields: [] },
          },
        },
      ],
    };

    expect(orders.kind).toBe('data');
    if (orders.kind !== 'data') throw new Error('unreachable');
    expect(orders.record?.paging).toBe('paged');
    expect(orders.views?.[0].id).not.toContain(SYSTEM_INSTANCE_ID_SEPARATOR);
  });

  it('marks a code-declared system view instance with the code revision', () => {
    const instance: ViewInstance = {
      id: `${SYSTEM_INSTANCE_ID_PREFIX}${SYSTEM_INSTANCE_ID_SEPARATOR}orders${SYSTEM_INSTANCE_ID_SEPARATOR}all`,
      definitionId: 'orders',
      title: 'All',
      scope: 'system',
      revision: CODE_REVISION,
      config: {
        ...filterBase,
        kind: 'record',
        refresh: { interval: null },
        sort: [],
        pageSize: 20,
        layout: 'table',
        table: { columns: [] },
        card: { title: 'id', fields: [] },
      },
    };

    expect(instance.id.startsWith(`${SYSTEM_INSTANCE_ID_PREFIX}:`)).toBe(true);
    expect(instance.scope).toBe('system');
  });
});
