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
import { SKU_BY_ID } from './catalog.js';
import { RETAIL_ORDER_ANALYSIS } from './views.js';
import {
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DateDiffUnit,
  DerivedExpressionType,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/wow-client/dsl';

const and = (...children: FilterLeaf[]): FilterTree => ({
  op: 'and',
  children,
});

/** 从「现在」往前数的一段：`近 3 个月`。 */
const recent = (field: string, amount: number, unit: 'day' | 'month') =>
  ({
    field,
    operator: FilterOperator.BETWEEN,
    value: { type: 'relative', amount, unit },
  }) as FilterLeaf;

const thisMonth = (field: string) =>
  ({
    field,
    operator: FilterOperator.BETWEEN,
    value: { type: 'preset', preset: 'monthToDate' },
  }) as FilterLeaf;

const GMV = 'state.amounts.payableAmount';
const REFUNDED = 'state.amounts.refundedAmount';
const PAID = 'state.amounts.paidAmount';
/** 已付款的单：没付款的单实付是 ¥0，算进来就是每周最低 ¥0（#3620）。 */
const PAID_ORDERS = {
  field: 'state.timing.paidAt',
  operator: FilterOperator.IS_NOT_NULL,
  value: null,
} as FilterLeaf;
/**
 * 付款到发货几小时：两个时刻之差（DATE_DIFF，N3），查询时现算，不再靠读模型
 * 预先算好的字段。
 */
const SHIP_HOURS = {
  type: AggregationExpressionType.DATE_DIFF,
  from: 'state.timing.paidAt',
  to: 'state.timing.shippedAt',
  unit: DateDiffUnit.HOUR,
} as const;
const ITEM_PAID = 'state.items.payAmount';

/**
 * The item a candle follows: the best seller of the last 12 months (长绒棉
 * 毛巾 单只装), sold in every week — `SHOWCASE_SKU` in 「图型全景」.
 */
const PRICED_SKU = 'SPU-009-1';
const PRICED_SKU_TITLE = SKU_BY_ID.get(PRICED_SKU)!.title;
const SALE_PRICE = 'state.items.salePrice';

const field = (name: string) =>
  ({ type: AggregationExpressionType.FIELD, field: name }) as const;

function sum(alias: string, name: string, label: string): AnalysisMetric {
  return {
    alias,
    type: AggregationMetricType.NUMERIC,
    function: AggregationFunction.SUM,
    expression: field(name),
    label,
  };
}

const orders: AnalysisMetric = {
  alias: 'orders',
  type: AggregationMetricType.COUNT,
  label: '订单数',
};

/** The orders a condition holds, counted. */
function counted(
  alias: string,
  label: string,
  where: FilterLeaf,
): AnalysisMetric {
  return {
    alias,
    type: AggregationMetricType.COUNT,
    label,
    filter: and(where),
  };
}

/** One metric over another, as a percentage. */
function share(
  alias: string,
  label: string,
  over: string,
  under: string,
): AnalysisMetric {
  return {
    alias,
    type: AggregationMetricType.DERIVED,
    label,
    format: { style: 'percent' },
    expression: {
      type: DerivedExpressionType.BINARY,
      operator: AggregationExpressionOperator.DIVIDE,
      left: { type: DerivedExpressionType.METRIC_REF, metric: over },
      right: { type: DerivedExpressionType.METRIC_REF, metric: under },
    },
  };
}

/** 一个字段的五个数：最小、P25、中位数、P75、最大（箱线图）。 */
function fiveNumbers(
  expression: AnalysisExpression,
  label: string,
): AnalysisMetric[] {
  const percentile = (at: number, word: string): AnalysisMetric => ({
    alias: `p${at}`,
    type: AggregationMetricType.PERCENTILE,
    percentile: at,
    expression,
    label: `${label}${word}`,
  });
  return [
    {
      alias: 'low',
      type: AggregationMetricType.NUMERIC,
      function: AggregationFunction.MIN,
      expression,
      label: `${label}最短`,
    },
    percentile(25, ' P25'),
    percentile(50, '中位数'),
    percentile(75, ' P75'),
    {
      alias: 'high',
      type: AggregationMetricType.NUMERIC,
      function: AggregationFunction.MAX,
      expression,
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

/**
 * 本月 GMV 的目标（刻度盘）：一个月的计划数，比 8 月的约 17.5 万高一成多。
 * 9 月走过 21 天（七成）时达成七成出头，正在进度上——原来的 30 万比双 11 那个
 * 月还高，刻度盘停在一半，读起来像落后了一大截（Storybook 审查 P2）。
 */
export const MONTH_GMV_TARGET = 200_000;

export const CHART_VIEWS: ViewInstance[] = [
  // 箱线图：各仓付款到发货要几个小时——不只看中位数，也看拖得最久的那头。
  // 四分位与中位数是 Wow 的近似百分位，图上写明。
  shared(
    CHART_VIEW_IDS.boxplot,
    '各仓付款到发货的分布（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [
        {
          type: AggregationGroupType.TERMS,
          field: 'state.warehouse',
          alias: 'warehouse',
        },
      ],
      metrics: fiveNumbers(SHIP_HOURS, '付款到发货') as [
        AnalysisMetric,
        ...AnalysisMetric[],
      ],
      sort: [{ alias: 'p50', direction: SortDirection.DESC }],
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
  // K 线：每周已付款的单每单实付多少，从首单开到末单收，中间最高、最低——
  // 一周里单笔实付的范围怎样移动。开与收是每周最早、最晚那一单的实付
  // （FIRST / LAST，N1）。是每单实付，不是单价：一单买几件都算一单；没付款的
  // 单不算，否则每周最低都是 ¥0（#3620）。
  shared(
    CHART_VIEW_IDS.candlestick,
    `「${PRICED_SKU_TITLE}」每周的成交单价 K 线（近 12 周）`,
    analysis({
      filter: and(recent('firstEventTime', 84, 'day'), PAID_ORDERS),
      // One item's price, week by week: every order's paid total was as
      // random a series as a basket is (Storybook review P2). A single
      // item's sale price moves with the discounts, and is what a candle
      // is for — the same item 「图型全景」 draws.
      elements: [
        {
          path: 'state.items',
          filter: and({
            field: 'state.items.skuId',
            operator: FilterOperator.IN,
            value: [PRICED_SKU],
          } as FilterLeaf),
        },
      ],
      groups: [
        {
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: 'state.items.placedAt',
          alias: 'week',
          unit: AggregationDateUnit.WEEK,
          label: '下单周',
        },
      ],
      metrics: [
        {
          alias: 'open',
          type: AggregationMetricType.FIRST,
          field: SALE_PRICE,
          orderBy: 'state.items.placedAt',
          label: '周内首单价',
        },
        {
          alias: 'high',
          type: AggregationMetricType.NUMERIC,
          function: AggregationFunction.MAX,
          expression: field(SALE_PRICE),
          label: '周内最高价',
        },
        {
          alias: 'low',
          type: AggregationMetricType.NUMERIC,
          function: AggregationFunction.MIN,
          expression: field(SALE_PRICE),
          label: '周内最低价',
        },
        {
          alias: 'close',
          type: AggregationMetricType.LAST,
          field: SALE_PRICE,
          orderBy: 'state.items.placedAt',
          label: '周内末单价',
        },
      ],
      sort: [{ alias: 'week', direction: SortDirection.ASC }],
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
        operator: FilterOperator.IS_NOT_NULL,
        value: null,
      }),
      groups: [
        {
          type: AggregationGroupType.HISTOGRAM,
          alias: 'hours',
          expression: SHIP_HOURS,
          interval: 4,
          label: '付款到发货',
        },
      ],
      metrics: [orders],
      sort: [{ alias: 'hours', direction: SortDirection.ASC }],
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
        operator: FilterOperator.EXPRESSION,
        value: {
          from: 'state.timing.paidAt',
          comparison: ComparisonOperator.GT,
          value: 48,
          unit: DateDiffUnit.HOUR,
        },
      }),
      groups: [
        {
          type: AggregationGroupType.TERMS,
          field: 'state.warehouse',
          alias: 'warehouse',
        },
      ],
      metrics: [orders],
      sort: [{ alias: 'orders', direction: SortDirection.DESC }],
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
  // 雷达：各渠道的经营轮廓——退款率、直降占比、老客占比、取消率，四根比率
  // 轴。规模量（单量、GMV）放上去只会比出谁大，轮廓都是同一个形状（Storybook
  // 审查 P2）；比率才说得出一个渠道「是什么样」。
  shared(
    CHART_VIEW_IDS.radar,
    '各渠道的经营轮廓：退款、直降、老客、取消各占几成（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [
        {
          type: AggregationGroupType.TERMS,
          field: 'state.channel',
          alias: 'channel',
        },
      ],
      metrics: [
        orders,
        counted('returning', '老客单', {
          field: 'state.buyer.isNewBuyer',
          operator: FilterOperator.EQ,
          value: false,
        }),
        counted('cancelled', '取消', {
          field: 'state.cancelReason',
          operator: FilterOperator.IS_NOT_NULL,
          value: null,
        }),
        sum('paid', PAID, '实付'),
        sum('refunded', REFUNDED, '已退'),
        sum('list', 'state.amounts.listAmount', '原价合计'),
        sum('itemDiscount', 'state.amounts.itemDiscount', '单品直降'),
        share('refundRate', '退款率', 'refunded', 'paid'),
        share('discountRate', '直降占比', 'itemDiscount', 'list'),
        share('returningRate', '老客占比', 'returning', 'orders'),
        share('cancelRate', '取消率', 'cancelled', 'orders'),
      ],
      chart: {
        type: 'radar',
        radar: {
          category: 'channel',
          metrics: [
            'refundRate',
            'discountRate',
            'returningRate',
            'cancelRate',
          ],
        },
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
        {
          type: AggregationGroupType.TERMS,
          field: 'state.address.province',
          alias: 'province',
        },
      ],
      metrics: [
        orders,
        sum('gmv', GMV, 'GMV'),
        sum('refunded', REFUNDED, '退款额'),
      ],
      sort: [{ alias: 'gmv', direction: SortDirection.DESC }],
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
        {
          type: AggregationGroupType.TERMS,
          field: 'state.items.category1',
          alias: 'category1',
        },
        {
          type: AggregationGroupType.TERMS,
          field: 'state.items.category2',
          alias: 'category2',
        },
      ],
      metrics: [sum('paid', ITEM_PAID, '实付')],
      sort: [{ alias: 'paid', direction: SortDirection.DESC }],
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
        {
          type: AggregationGroupType.TERMS,
          field: 'state.items.category1',
          alias: 'category1',
        },
        {
          type: AggregationGroupType.TERMS,
          field: 'state.items.category2',
          alias: 'category2',
        },
      ],
      metrics: [sum('paid', ITEM_PAID, '实付')],
      sort: [{ alias: 'paid', direction: SortDirection.DESC }],
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
        {
          type: AggregationGroupType.TERMS,
          field: 'state.channel',
          alias: 'channel',
        },
        {
          type: AggregationGroupType.TERMS,
          field: 'state.payment.method',
          alias: 'payment',
        },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'gmv', direction: SortDirection.DESC }],
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
    '每日 GMV（日历，2025 年起）',
    analysis({
      // From 2025 on: two blocks, the first a whole year. All 25 months
      // drew 2024 as a year of empty squares with four months in its
      // corner (Storybook review P2).
      filter: and({
        field: 'firstEventTime',
        operator: FilterOperator.GTE,
        value: { type: 'absolute', from: '2025-01-01T00:00:00+08:00' },
      } as FilterLeaf),
      groups: [
        {
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: 'firstEventTime',
          alias: 'day',
          unit: AggregationDateUnit.DAY,
          label: '日期',
        },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'day', direction: SortDirection.ASC }],
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
          type: AggregationGroupType.DATE_HISTOGRAM,
          field: 'firstEventTime',
          alias: 'week',
          unit: AggregationDateUnit.WEEK,
          label: '周',
        },
        {
          type: AggregationGroupType.TERMS,
          field: 'state.channel',
          alias: 'channel',
        },
      ],
      metrics: [sum('gmv', GMV, 'GMV')],
      sort: [{ alias: 'week', direction: SortDirection.ASC }],
      limit: 1000,
      chart: {
        type: 'themeRiver',
        themeRiver: { x: 'week', splitBy: 'channel', value: 'gmv' },
      },
    }),
  ),
];
