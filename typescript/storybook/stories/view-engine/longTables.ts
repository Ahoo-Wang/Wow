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

/* --------------------------------------------------------------------------
 * 长表：一张一千、五千、一万行的分析表，外加一页两百条的记录表。
 *
 * 分析结果最多 `maxAnalysisRows` 行（缺省一千，D42；宿主调高服务端的守卫后最多
 * 到 Wow 的上限一万），多于一千行时表格只画看得见的行
 * （D44）；记录视图一页最多 `maxPageSize`（两百）条。这里量的是这两个上限画出
 * 来够不够快、滚得顺不顺、点表头排序与方向键走行要多久（ui/analysis.md「长表」）。
 *
 * 分析那份按客户分组：一万个客户各一行，两列维度、三列指标，外加合计行——
 * 「按客户看销售额」是零售里真会问出一万行的那种问题。行是**算出来**的，每一
 * 格只由行号决定；数据源只答它认得的两个问题（按客户与地区分组，或不分组的合
 * 计），按查询的排序排好、按 `limit` 截断，别的一律拒绝。
 * ------------------------------------------------------------------------ */

import {
  AggregationFunction,
  AggregationGroupType,
  SortDirection,
} from '@ahoo-wang/wow-client';
import type {
  AnalysisViewConfig,
  DashboardViewConfig,
  DataViewDefinition,
  RecordData,
  ViewInstance,
  ViewSource,
} from '@ahoo-wang/wow-view-engine';
import {
  WAYBILLS,
  analysisConfig,
  dashboardConfig,
  waybillConfig,
  waybillsDefinition,
} from './fixtures.js';
import { rowSource } from './rowSource.js';

/** The lengths the long table is measured at; the last is Wow's own ceiling. */
export const LONG_TABLE_ROWS = [1_000, 5_000, 10_000] as const;

export type LongTableRows = (typeof LONG_TABLE_ROWS)[number];

const REGIONS = [
  { value: 'CN-EAST', label: '华东' },
  { value: 'CN-NORTH', label: '华北' },
  { value: 'CN-SOUTH', label: '华南' },
  { value: 'CN-WEST', label: '西南' },
];

/** 销售记录：客户、地区与金额，按客户分析。 */
export const salesDefinition: DataViewDefinition = {
  id: 'long-sales',
  title: '销售',
  recordNoun: '销售单',
  kind: 'data',
  source: 'long-sales',
  fields: [
    { name: 'id', label: '单号', kind: 'string', sortable: true },
    { name: 'customer', label: '客户', kind: 'string' },
    { name: 'region', label: '地区', kind: 'enum', options: REGIONS },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      {
        field: 'customer',
        groups: [AggregationGroupType.TERMS],
        functions: [],
      },
      { field: 'region', groups: [AggregationGroupType.TERMS], functions: [] },
      {
        field: 'amount',
        groups: [],
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      },
    ],
  },
};

/** 按客户与地区：单数、金额合计与客单价，按金额降序，带合计行。 */
export function salesConfig(rows: LongTableRows): AnalysisViewConfig {
  return analysisConfig({
    layout: 'table',
    groups: [
      { type: 'TERMS', field: 'customer', alias: 'customer' },
      { type: 'TERMS', field: 'region', alias: 'region' },
    ],
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
      {
        alias: 'average',
        type: 'NUMERIC',
        function: 'AVG',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    sort: [{ alias: 'amount', direction: SortDirection.DESC }],
    limit: rows,
    table: { columns: [], totals: true },
  });
}

/** 销售上的分析视图。 */
export function salesView(rows: LongTableRows): ViewInstance {
  return {
    id: 'long-sales-analysis',
    definitionId: salesDefinition.id,
    title: `按客户销售（${rows} 行）`,
    scope: 'shared',
    revision: '1',
    config: salesConfig(rows),
  };
}

/** 只有这一个分析面板的仪表盘：面板里是同一张长表。 */
export function salesBoardConfig(): DashboardViewConfig {
  return dashboardConfig({
    fields: [],
    panels: [
      {
        id: 'sales',
        kind: 'view',
        title: '按客户销售',
        instanceId: 'long-sales-analysis',
        bindings: [],
        layout: { x: 0, y: 0, w: 24, h: 8 },
      },
    ],
  });
}

/** 那块仪表盘。 */
export const salesBoard: ViewInstance = {
  id: 'long-sales-board',
  definitionId: 'overview',
  title: '长表面板',
  scope: 'shared',
  revision: '1',
  config: salesBoardConfig(),
};

/** One customer's row, decided by its index alone. */
function customerRow(index: number): RecordData {
  const orders = 1 + ((index * 7) % 23);
  const average = 120 + ((index * 37) % 880);
  return {
    customer: `客户 ${String(index + 1).padStart(5, '0')}`,
    region: REGIONS[index % REGIONS.length].value,
    orders,
    amount: orders * average,
    average,
  };
}

/**
 * The grouped answer, sorted as asked and cut at the limit, and the one row
 * of the ungrouped totals. Anything else is refused, so a question the story
 * starts to ask shows as a failure rather than as a plausible wrong answer.
 */
export function salesSource(rows: LongTableRows): ViewSource {
  const all = Array.from({ length: rows }, (_, index) => customerRow(index));
  const refuse = async (): Promise<never> => {
    throw new Error('The long-table story source answers two questions only.');
  };
  return {
    paged: refuse,
    cursor: refuse,
    aggregate: async query => {
      const groups = (query.groupBy ?? []).map(group => group.alias);
      if (groups.length === 0) {
        const orders = all.reduce((sum, row) => sum + Number(row.orders), 0);
        const amount = all.reduce((sum, row) => sum + Number(row.amount), 0);
        return [{ orders, amount, average: amount / orders }];
      }
      if (groups.join() !== 'customer,region') return refuse();
      const sorted = [...all];
      const sort = query.sort ?? [];
      if (sort.length > 0)
        sorted.sort((a, b) => {
          for (const { field, direction } of sort) {
            const x = a[field] as number | string;
            const y = b[field] as number | string;
            if (x === y) continue;
            const order = x < y ? -1 : 1;
            return direction === SortDirection.DESC ? -order : order;
          }
          return 0;
        });
      return sorted.slice(0, query.limit ?? sorted.length);
    },
  };
}

/** 一千条运单：五十条那份按行号重复，单号各不相同。 */
const MANY_WAYBILLS: RecordData[] = Array.from(
  { length: 1_000 },
  (_, index) => {
    const base = WAYBILLS[index % WAYBILLS.length];
    return {
      ...base,
      id: `YD-${10_001 + index}`,
      orderNo: `SO-${20_001 + index}`,
    };
  },
);

/** 运单的记录视图，一页两百条——`maxPageSize`。 */
export const waybillPageView: ViewInstance = {
  id: 'waybills-page',
  definitionId: waybillsDefinition.id,
  title: '全部运单（每页 200 条）',
  scope: 'shared',
  revision: '1',
  config: waybillConfig({ pageSize: 200 }),
};

/** 一千条运单背后的数据源，按查询过滤、排序、分页。 */
export function manyWaybillsSource(): ViewSource {
  return rowSource(MANY_WAYBILLS);
}
