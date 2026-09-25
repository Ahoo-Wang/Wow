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
 * 图型陈列（view-engine D41）：零售订单上每种新图型各一个真问题，一张已存
 * 分析一种图，打开就是答案。和分析工作台的 A-01～A-18 分开放：那边是分析师
 * 的问题清单，这边是「这种图拿来回答什么」。
 * ------------------------------------------------------------------------ */

import type {
  AnalysisMetric,
  AnalysisViewConfig,
  FilterLeaf,
  FilterTree,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { RETAIL_ORDER_ANALYSIS } from './views.js';

const and = (...children: FilterLeaf[]): FilterTree => ({
  op: 'and',
  children,
});

/** 从「现在」往前数的一段：`近 3 个月`。 */
const recent = (field: string, amount: number, unit: 'day' | 'month') =>
  ({
    field,
    operator: 'BETWEEN',
    value: { type: 'relative', amount, unit },
  }) as FilterLeaf;

const thisMonth = (field: string) =>
  ({
    field,
    operator: 'BETWEEN',
    value: { type: 'preset', preset: 'monthToDate' },
  }) as FilterLeaf;

const GMV = 'state.amounts.payableAmount';
const REFUNDED = 'state.amounts.refundedAmount';
const SHIP_HOURS = 'state.payToShipHours';
const ITEM_PAID = 'state.items.payAmount';

const field = (name: string) => ({ type: 'FIELD', field: name }) as const;

function sum(alias: string, name: string, label: string): AnalysisMetric {
  return {
    alias,
    type: 'NUMERIC',
    function: 'SUM',
    expression: field(name),
    label,
  };
}

const orders: AnalysisMetric = {
  alias: 'orders',
  type: 'COUNT',
  label: '订单数',
};

/** 一个字段的五个数：最小、P25、中位数、P75、最大（箱线图）。 */
function fiveNumbers(name: string, label: string): AnalysisMetric[] {
  const percentile = (at: number, word: string): AnalysisMetric => ({
    alias: `p${at}`,
    type: 'PERCENTILE',
    percentile: at,
    expression: field(name),
    label: `${label}${word}`,
  });
  return [
    {
      alias: 'low',
      type: 'NUMERIC',
      function: 'MIN',
      expression: field(name),
      label: `${label}最短`,
    },
    percentile(25, ' P25'),
    percentile(50, '中位数'),
    percentile(75, ' P75'),
    {
      alias: 'high',
      type: 'NUMERIC',
      function: 'MAX',
      expression: field(name),
      label: `${label}最长`,
    },
  ];
}

function analysis(
  config: Partial<AnalysisViewConfig> &
    Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'chart'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: and(),
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    ...config,
  };
}

function shared(
  id: string,
  title: string,
  config: AnalysisViewConfig,
): ViewInstance {
  return {
    id,
    definitionId: RETAIL_ORDER_ANALYSIS,
    title,
    scope: 'shared',
    revision: '1',
    config,
  };
}

/** 陈列里每张视图的 id，故事按它打开。 */
export const CHART_VIEW_IDS = {
  boxplot: 'chart-boxplot-ship-hours',
  gauge: 'chart-gauge-month-gmv',
  radar: 'chart-radar-channels',
  parallel: 'chart-parallel-provinces',
  sunburst: 'chart-sunburst-categories',
  tree: 'chart-tree-categories',
  sankey: 'chart-sankey-channel-payment',
} as const;

/** 本月 GMV 的目标（刻度盘）：一个月的计划数。 */
export const MONTH_GMV_TARGET = 300_000;

export const CHART_VIEWS: ViewInstance[] = [
  // 箱线图：各仓付款到发货要几个小时——不只看中位数，也看拖得最久的那头。
  // 四分位与中位数是 Wow 的近似百分位，图上写明。
  shared(
    CHART_VIEW_IDS.boxplot,
    '各仓付款到发货的分布（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [{ type: 'TERMS', field: 'state.warehouse', alias: 'warehouse' }],
      metrics: fiveNumbers(SHIP_HOURS, '付款到发货') as [
        AnalysisMetric,
        ...AnalysisMetric[],
      ],
      sort: [{ alias: 'p50', direction: 'DESC' }],
      chart: {
        type: 'boxplot',
        boxplot: {
          category: 'warehouse',
          low: 'low',
          q1: 'p25',
          median: 'p50',
          q3: 'p75',
          high: 'high',
        },
      },
    }),
  ),
  // 刻度盘：本月 GMV 走到月目标的哪儿了。指标卡说数字与变化，刻度盘说位置。
  shared(
    CHART_VIEW_IDS.gauge,
    '本月 GMV 达成',
    analysis({
      filter: and(thisMonth('firstEventTime')),
      groups: [],
      metrics: [sum('gmv', GMV, 'GMV')],
      chart: {
        type: 'gauge',
        gauge: { metric: 'gmv', target: MONTH_GMV_TARGET },
      },
    }),
  ),
  // 雷达：各渠道的经营轮廓——单量、GMV、退款各自一根轴、各自的刻度。
  shared(
    CHART_VIEW_IDS.radar,
    '各渠道的经营轮廓（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [{ type: 'TERMS', field: 'state.channel', alias: 'channel' }],
      metrics: [
        orders,
        sum('gmv', GMV, 'GMV'),
        sum('refunded', REFUNDED, '退款额'),
      ],
      chart: {
        type: 'radar',
        radar: { category: 'channel', metrics: ['orders', 'gmv', 'refunded'] },
      },
    }),
  ),
  // 平行坐标：每个省份一条线，跨单量、GMV、退款额——哪几个省退得多卖得少。
  shared(
    CHART_VIEW_IDS.parallel,
    '各省份的单量、GMV 与退款（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [
        { type: 'TERMS', field: 'state.address.province', alias: 'province' },
      ],
      metrics: [
        orders,
        sum('gmv', GMV, 'GMV'),
        sum('refunded', REFUNDED, '退款额'),
      ],
      sort: [{ alias: 'gmv', direction: 'DESC' }],
      chart: {
        type: 'parallel',
        parallel: {
          category: 'province',
          metrics: ['orders', 'gmv', 'refunded'],
        },
      },
    }),
  ),
  // 旭日图：近 12 个月的实付由哪些品类、哪些子类构成——里圈一级类目，外圈
  // 二级类目。按商品行展开，以订单行为单位。
  shared(
    CHART_VIEW_IDS.sunburst,
    '品类 → 子类的实付构成（近 12 个月）',
    analysis({
      filter: and(recent('firstEventTime', 12, 'month')),
      elements: [{ path: 'state.items' }],
      groups: [
        { type: 'TERMS', field: 'state.items.category1', alias: 'category1' },
        { type: 'TERMS', field: 'state.items.category2', alias: 'category2' },
      ],
      metrics: [sum('paid', ITEM_PAID, '实付')],
      sort: [{ alias: 'paid', direction: 'DESC' }],
      chart: {
        type: 'sunburst',
        sunburst: { levels: ['category1', 'category2'], value: 'paid' },
      },
    }),
  ),
  // 树图：同一个问题画成从左到右的分解，每个子类写着自己的数。
  shared(
    CHART_VIEW_IDS.tree,
    '品类 → 子类的实付分解（近 12 个月）',
    analysis({
      filter: and(recent('firstEventTime', 12, 'month')),
      elements: [{ path: 'state.items' }],
      groups: [
        { type: 'TERMS', field: 'state.items.category1', alias: 'category1' },
        { type: 'TERMS', field: 'state.items.category2', alias: 'category2' },
      ],
      metrics: [sum('paid', ITEM_PAID, '实付')],
      sort: [{ alias: 'paid', direction: 'DESC' }],
      chart: {
        type: 'tree',
        tree: { levels: ['category1', 'category2'], value: 'paid' },
      },
    }),
  ),
  // 桑基图：近 3 个月的 GMV 从哪个渠道流向哪种支付方式。
  shared(
    CHART_VIEW_IDS.sankey,
    '渠道 → 支付方式的 GMV（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [
        { type: 'TERMS', field: 'state.channel', alias: 'channel' },
        { type: 'TERMS', field: 'state.payment.method', alias: 'payment' },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'gmv', direction: 'DESC' }],
      chart: {
        type: 'sankey',
        sankey: { levels: ['channel', 'payment'], value: 'gmv' },
      },
    }),
  ),
];
