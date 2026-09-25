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
} from '@ahoo-wang/wow-client';
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
  VIEW_AUDIENCES,
  VIEW_KINDS,
  VIEW_SCOPES,
  audienceOf,
  isSystemScope,
  autoRunMembers,
  presentationMembers,
  toSummary,
  type AnalysisViewConfig,
  type ChartSpec,
  type DashboardViewConfig,
  type FieldDefinition,
  type FilterOperatorName,
  type RecordViewConfig,
  type DataViewConfigBase,
  type ViewConfig,
  type ViewDefinition,
  type ViewInstance,
  type ViewKind,
} from '../src/index.js';
import { analysisConfig, dashboardConfig, recordConfig } from './fixtures.js';

const filterBase: Pick<DataViewConfigBase, 'filter' | 'filterMode'> = {
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
};

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
      'waterfall',
      'treemap',
      'boxplot',
      'gauge',
      'radar',
      'parallel',
      'sunburst',
      'tree',
      'sankey',
    ];
    for (const type of CHART_TYPES) {
      expect(optional).toContain(CHART_FAMILY[type]);
    }
    expect(CHART_FAMILY.bar).toBe('cartesian');
    expect(CHART_FAMILY.combo).toBe('cartesian');
    expect(CHART_FAMILY.funnel).toBe('funnel');
    expect(CHART_FAMILY.waterfall).toBe('waterfall');
    expect(CHART_FAMILY.treemap).toBe('treemap');
    expect(CHART_FAMILY.boxplot).toBe('boxplot');
    expect(CHART_FAMILY.gauge).toBe('gauge');
    expect(CHART_FAMILY.radar).toBe('radar');
    expect(CHART_FAMILY.parallel).toBe('parallel');
  });

  it('keeps the refresh bounds inside a 32-bit millisecond timer', () => {
    const { minRefreshInterval, maxRefreshInterval } = DEFAULT_RUNTIME_LIMITS;
    expect(minRefreshInterval).toBeGreaterThan(0);
    expect(maxRefreshInterval).toBeGreaterThan(minRefreshInterval);
    expect(maxRefreshInterval * 1000).toBeLessThan(MAX_TIMER_DELAY_MS);
  });

  it('gives every budget a positive integer default, and every ladder ascending rungs', () => {
    for (const [name, value] of Object.entries(DEFAULT_RUNTIME_LIMITS)) {
      // The one switch among the numbers: exports neutralize formulas unless
      // a host says otherwise (D37).
      if (typeof value === 'boolean') {
        expect([name, value]).toEqual(['exportNeutralizeFormulas', true]);
        continue;
      }
      // A ladder is the product's rungs (page sizes, refresh cadences):
      // positive whole numbers, in the order a menu lists them.
      const rungs = Array.isArray(value) ? value : [value];
      expect(rungs.length, name).toBeGreaterThan(0);
      for (const rung of rungs) {
        expect(Number.isInteger(rung), name).toBe(true);
        expect(rung, name).toBeGreaterThan(0);
      }
      expect(
        [...rungs].sort((a, b) => a - b),
        name,
      ).toEqual(rungs);
    }
  });

  it('reserves an instance id namespace for code-declared system views', () => {
    expect(SYSTEM_INSTANCE_ID_PREFIX).toBe('system');
    expect(SYSTEM_INSTANCE_ID_SEPARATOR).toBe(':');
    expect(CODE_REVISION).toBe('code');
  });
});

describe('scope answers two questions', () => {
  it('puts a system view in front of everyone, never in a group of its own', () => {
    expect(VIEW_AUDIENCES).toEqual(['personal', 'shared']);
    expect(VIEW_SCOPES.map(audienceOf)).toEqual([
      'shared',
      'shared',
      'personal',
    ]);
  });

  it('names only the system scope as the one a user did not configure', () => {
    expect(VIEW_SCOPES.filter(isSystemScope)).toEqual(['system']);
  });
});

describe('a summary', () => {
  const instance = (config: ViewConfig): ViewInstance => ({
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: '1',
    config,
  });

  const record: ViewConfig = {
    ...filterBase,
    kind: 'record',
    refresh: { interval: null },
    sort: [],
    pageSize: 20,
    layout: 'table',
    table: { columns: [] },
    card: { title: 'id', fields: [] },
  };

  const dashboard: ViewConfig = {
    kind: 'dashboard',
    columns: 24,
    fixed: { op: 'and', children: [] },
    tabs: [],
    refresh: { interval: null },
    fields: [],
    panels: [],
  };

  it('carries the kind of the config it names, and not the config', () => {
    expect(toSummary(instance(record)).kind).toBe('record');
    expect(toSummary(instance(dashboard)).kind).toBe('dashboard');
    expect('config' in toSummary(instance(record))).toBe(false);
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
      kind: 'dashboard',
      columns: 24,
      fixed: { op: 'and', children: [] },
      tabs: [],
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

  /**
   * The members that only draw the result the query already returned, which
   * `comparePending` skips so that switching them raises no "changed, not
   * applied" dot. They used to be string comparisons inside that function
   * (A6); declared beside the config types, the `satisfies` on each list
   * refuses a member the config does not have — and this walks the other
   * way, so a list naming a member that has since been renamed fails here.
   */
  it('names presentation-only members the config actually has', () => {
    const members: Record<ViewKind, readonly string[]> = {
      record: Object.keys(recordConfig()),
      analysis: Object.keys(analysisConfig()),
      dashboard: Object.keys(dashboardConfig()),
    };

    for (const kind of VIEW_KINDS) {
      expect(
        presentationMembers(kind).filter(
          member => !members[kind].includes(member),
        ),
      ).toEqual([]);
    }
  });

  it('counts the editor mode as presentation for both data views', () => {
    // The one member the two share: the condition builder shows the same
    // tree either way, so the mode never reaches a query. A dashboard has
    // no conditions of its own and so no mode (D27).
    expect(presentationMembers('record')).toContain('filterMode');
    expect(presentationMembers('analysis')).toContain('filterMode');
    expect(presentationMembers('dashboard')).toEqual([]);
    // How a result is looked at is presentation in both kinds that have a
    // result: a record view draws the rows it has as a table or as cards,
    // and an analysis draws the rows it has as a table or as a chart of
    // whichever type (D20). A dashboard has no layout of its own.
    expect(presentationMembers('record')).toContain('layout');
    expect(presentationMembers('analysis')).toContain('layout');
    expect(presentationMembers('analysis')).toContain('chart');
    expect(presentationMembers('dashboard')).not.toContain('layout');
    // `table` is not among them: its totals row is a query of its own.
    expect(presentationMembers('analysis')).not.toContain('table');
  });

  /**
   * The members that run again on their own a moment after they change
   * (「改了就跑」, D20), declared per kind beside the config types so the
   * runtime carries no rule of any one kind: an analysis's question, and
   * nothing of a record view or a dashboard.
   */
  it('names the question members that run on their own, per kind', () => {
    expect(autoRunMembers('analysis')).toEqual([
      'elements',
      'groups',
      'metrics',
      'having',
      'sort',
      'limit',
      'table',
    ]);
    // The range waits for Apply (D20); a presentation member never runs.
    for (const member of ['filter', 'filterMode', 'layout', 'chart'])
      expect(autoRunMembers('analysis')).not.toContain(member);
    for (const member of autoRunMembers('analysis'))
      expect(presentationMembers('analysis')).not.toContain(member);
    expect(autoRunMembers('record')).toEqual([]);
    expect(autoRunMembers('dashboard')).toEqual([]);
    expect(autoRunMembers('unknown' as ViewKind)).toEqual([]);
    // Every name is one the config has — including the optional members.
    const full = Object.keys(
      analysisConfig({
        elements: [],
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GT',
          value: 1,
        },
      }),
    );
    for (const member of autoRunMembers('analysis'))
      expect(full).toContain(member);
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
        { name: 'id', label: 'Order', kind: 'string', sortable: true },
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

  /**
   * D17-11. A field declares what it is, not which control draws it: the
   * filter editor is picked from the kind, the operator and the value
   * shape, and `FieldDefinition.editor` — declared, never read — named an
   * editor the field would have got anyway. It is gone, so writing it is
   * now a type error; an old definition that still does is a warning at
   * admission rather than a refusal (`test/definition.test.ts`).
   */
  it('declares no filter editor key on a field', () => {
    const amount: FieldDefinition = {
      name: 'amount',
      label: 'Amount',
      kind: 'number',
      summary: ['SUM'],
      // @ts-expect-error `editor` was removed from FieldDefinition (D17-11).
      editor: 'number-range',
    };

    expect(amount.kind).toBe('number');
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
