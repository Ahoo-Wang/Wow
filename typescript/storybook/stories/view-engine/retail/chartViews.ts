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
  AnalysisExpression,
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
const PAID = 'state.amounts.paidAmount';
/**
 * 付款到发货几小时：两个时刻之差（DATE_DIFF，N3），查询时现算，不再靠读模型
 * 预先算好的字段。
 */
const SHIP_HOURS = {
  type: 'DATE_DIFF',
  from: 'state.timing.paidAt',
  to: 'state.timing.shippedAt',
  unit: 'HOUR',
} as const;
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
function fiveNumbers(
  expression: AnalysisExpression,
  label: string,
): AnalysisMetric[] {
  const percentile = (at: number, word: string): AnalysisMetric => ({
    alias: `p${at}`,
    type: 'PERCENTILE',
    percentile: at,
    expression,
    label: `${label}${word}`,
  });
  return [
    {
      alias: 'low',
      type: 'NUMERIC',
      function: 'MIN',
      expression,
      label: `${label}最短`,
    },
    percentile(25, ' P25'),
    percentile(50, '中位数'),
    percentile(75, ' P75'),
    {
      alias: 'high',
      type: 'NUMERIC',
      function: 'MAX',
      expression,
      label: `${label}最长`,
    },
  ];
}

/**
 * 一个字段的四个数：期初值、最高、最低、期末值（K 线），开与收按下单时间
 * 先后取。
 */
function ohlc(name: string, label: string): AnalysisMetric[] {
  const edge = (type: 'FIRST' | 'LAST', alias: string, word: string) =>
    ({
      alias,
      type,
      field: name,
      orderBy: 'firstEventTime',
      label: `${label}${word}`,
    }) as AnalysisMetric;
  const extreme = (fn: 'MAX' | 'MIN', alias: string, word: string) =>
    ({
      alias,
      type: 'NUMERIC',
      function: fn,
      expression: field(name),
      label: `${label}${word}`,
    }) as AnalysisMetric;
  return [
    edge('FIRST', 'open', '（首单）'),
    extreme('MAX', 'high', '（最高）'),
    extreme('MIN', 'low', '（最低）'),
    edge('LAST', 'close', '（末单）'),
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
  durationBands: 'chart-histogram-ship-hours',
  lateShipments: 'chart-late-shipments',
  candlestick: 'chart-candlestick-weekly-paid',
  gauge: 'chart-gauge-month-gmv',
  radar: 'chart-radar-channels',
  parallel: 'chart-parallel-provinces',
  sunburst: 'chart-sunburst-categories',
  tree: 'chart-tree-categories',
  sankey: 'chart-sankey-channel-payment',
  calendar: 'chart-calendar-daily-gmv',
  themeRiver: 'chart-river-channel-gmv',
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
  // K 线：每周成交单价从首单开到末单收，中间最高、最低——一周里价格带怎样
  // 移动。开与收是每周最早、最晚那一单的实付（FIRST / LAST，N1）。
  shared(
    CHART_VIEW_IDS.candlestick,
    '每周成交单价 K 线（近 12 周）',
    analysis({
      filter: and(recent('firstEventTime', 84, 'day')),
      groups: [
        {
          type: 'DATE_HISTOGRAM',
          field: 'firstEventTime',
          alias: 'week',
          unit: 'WEEK',
          label: '下单周',
        },
      ],
      metrics: ohlc(PAID, '实付') as [AnalysisMetric, ...AnalysisMetric[]],
      sort: [{ alias: 'week', direction: 'ASC' }],
      chart: {
        type: 'candlestick',
        candlestick: {
          x: 'week',
          open: 'open',
          high: 'high',
          low: 'low',
          close: 'close',
        },
      },
    }),
  ),
  // 直方分组：付款到发货落在哪一档——每 4 小时一档（两个时刻之差按区间分组，
  // N3）。48 小时之后的几档就是超时发货。
  shared(
    CHART_VIEW_IDS.durationBands,
    '付款到发货的时长分布（近 3 个月已发货的单，每 4 小时一档）',
    analysis({
      // 没发货的单没有这段时长：只看已发货的。
      filter: and(recent('firstEventTime', 3, 'month'), {
        field: 'state.timing.shippedAt',
        operator: 'IS_NOT_NULL',
        value: null,
      }),
      groups: [
        {
          type: 'HISTOGRAM',
          alias: 'hours',
          expression: SHIP_HOURS,
          interval: 4,
          label: '付款到发货',
        },
      ],
      metrics: [orders],
      sort: [{ alias: 'hours', direction: 'ASC' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'hours', series: [{ metric: 'orders' }] },
      },
    }),
  ),
  // 条件里的两个时刻之差：付款后超过 48 小时才发货的单，按仓库（EXPRESSION，
  // N3）——「发货时间 距 付款时间 > 48 小时」写在范围里，不靠读模型的字段。
  shared(
    CHART_VIEW_IDS.lateShipments,
    '付款后超过 48 小时才发货的单（近 3 个月，按仓库）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month'), {
        field: 'state.timing.shippedAt',
        operator: 'EXPRESSION',
        value: {
          from: 'state.timing.paidAt',
          comparison: 'GT',
          value: 48,
          unit: 'HOUR',
        },
      }),
      groups: [{ type: 'TERMS', field: 'state.warehouse', alias: 'warehouse' }],
      metrics: [orders],
      sort: [{ alias: 'orders', direction: 'DESC' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
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
  // 日历热力图：25 个月里每一天的 GMV——双 11、年货节一眼看见，春节停运那
  // 几天是空的。
  shared(
    CHART_VIEW_IDS.calendar,
    '每日 GMV（日历）',
    analysis({
      groups: [
        {
          type: 'DATE_HISTOGRAM',
          field: 'firstEventTime',
          alias: 'day',
          unit: 'DAY',
          label: '日期',
        },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'day', direction: 'ASC' }],
      limit: 1000,
      chart: { type: 'calendar', calendar: { date: 'day', value: 'gmv' } },
    }),
  ),
  // 河流图：近 12 个月各渠道每周的 GMV——直播间从细流涨成主干。
  shared(
    CHART_VIEW_IDS.themeRiver,
    '各渠道每周 GMV（近 12 个月）',
    analysis({
      filter: and(recent('firstEventTime', 12, 'month')),
      groups: [
        {
          type: 'DATE_HISTOGRAM',
          field: 'firstEventTime',
          alias: 'week',
          unit: 'WEEK',
          label: '周',
        },
        { type: 'TERMS', field: 'state.channel', alias: 'channel' },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'week', direction: 'ASC' }],
      limit: 1000,
      chart: {
        type: 'themeRiver',
        themeRiver: { x: 'week', splitBy: 'channel', value: 'gmv' },
      },
    }),
  ),
];
