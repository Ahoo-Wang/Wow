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
 * 图型全景（用户 2026-09-26 定「按推荐」）：一块零售看板，22 种图型各用一次，
 * 每张图的标题就是它回答的分析问题，分四个页签。
 *
 * 依据是第二轮审查逐图型的结论：「能回答」的引用已经验证过的已存分析
 * （`saved`）；「部分／不能」的换成它真正能回答的问题，建成板上自有的面板
 * （`owned`）——只取完整月、完整周，雷达的轴都是比率，平行坐标只画 5 个有名字
 * 的渠道，K 线看一件商品每周的成交单价。
 *
 * 这个文件不引用 `boards.ts`（它反过来引用这里），所以面板的小工具自带一份。
 * ------------------------------------------------------------------------ */

import type {
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  DashboardField,
  DashboardPanel,
  DashboardViewConfig,
  DerivedFormat,
  FilterNode,
  PanelBinding,
  PanelClick,
  PanelLayout,
  ChartType,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { CHART_VIEWS, CHART_VIEW_IDS } from './chartViews.js';
import { SKU_BY_ID } from './catalog.js';
import {
  ANALYSIS_VIEWS,
  RETAIL_ORDER_ANALYSIS,
  retailOrdersDefinition,
} from './views.js';

/** 板子的 id。 */
export const SHOWCASE = 'retail-showcase';

/** 四个页签：一个页签回答一类问题。 */
export const SHOWCASE_TABS = {
  trend: '走势：生意怎么样',
  mix: '构成：钱从哪里来',
  spread: '分布与关系：差异在哪',
  region: '地域与转化',
} as const;

/** 地图规格里中国省级地图的名字；宿主注册它（`chinaProvinces.ts`）。 */
export const SHOWCASE_MAP = 'china';

/** 上月（2026 年 8 月）GMV 的月目标。 */
export const SHOWCASE_MONTH_TARGET = 200_000;

/** 日 GMV 的目标区间（元）：平日落在这里算正常。 */
export const SHOWCASE_DAY_TARGET = { from: 4_000, to: 8_000 } as const;

/**
 * K 线看的那件商品：近 12 个月卖得最多的一件（长绒棉毛巾 单只装，每周至少
 * 4 行成交）。
 */
export const SHOWCASE_SKU = 'SPU-009-1';

// ---------------------------------------------------------------- 小工具

const and = (...children: FilterNode[]) => ({
  op: 'and' as const,
  children,
});

const leaf = (field: string, operator: string, value: unknown): FilterNode =>
  ({ field, operator, value }) as FilterNode;

/** 一段钉死的日期：[from, to]，上海时间的整天。 */
const days = (field: string, from: string, to: string): FilterNode =>
  leaf(field, 'BETWEEN', {
    type: 'absolute',
    from: `${from}T00:00:00+08:00`,
    to: `${to}T23:59:59.999+08:00`,
  });

/** 近 12 个完整月：2025 年 9 月到 2026 年 8 月（「现在」是 9 月 22 日）。 */
const TWELVE_FULL_MONTHS = (field: string) =>
  days(field, '2025-09-01', '2026-08-31');

/** 52 个完整的周：2025-09-22（周一）到 2026-09-20（周日）。 */
const FIFTY_TWO_WEEKS = (field: string) =>
  days(field, '2025-09-22', '2026-09-20');

/** 到上个月为止：完整的月，本月还没过完不算。 */
const THROUGH_LAST_MONTH = (field: string) =>
  leaf(field, 'LTE', { type: 'preset', preset: 'lastMonth' });

const PAID = leaf('state.timing.paidAt', 'IS_NOT_NULL', null);

const GMV = 'state.amounts.payableAmount';

const field = (name: string) => ({ type: 'FIELD', field: name }) as const;

const sum = (
  alias: string,
  name: string,
  label: string,
  filter?: FilterNode[],
): AnalysisMetric => ({
  type: 'NUMERIC',
  alias,
  function: 'SUM',
  expression: field(name),
  label,
  ...(filter ? { filter: and(...filter) } : {}),
});

const count = (
  alias: string,
  label: string,
  filter?: FilterNode[],
): AnalysisMetric => ({
  type: 'COUNT',
  alias,
  label,
  ...(filter ? { filter: and(...filter) } : {}),
});

const ratio = (
  alias: string,
  label: string,
  over: string,
  under: string,
  format: DerivedFormat = { style: 'percent' },
): AnalysisMetric => ({
  type: 'DERIVED',
  alias,
  label,
  format,
  expression: {
    type: 'BINARY',
    operator: 'DIVIDE',
    left: { type: 'METRIC_REF', metric: over },
    right: { type: 'METRIC_REF', metric: under },
  },
});

const byTerms = (
  name: string,
  alias: string,
  label?: string,
): AnalysisGroup => ({
  type: 'TERMS',
  field: name,
  alias,
  ...(label ? { label } : {}),
});

const byDate = (
  name: string,
  alias: string,
  unit: 'DAY' | 'WEEK' | 'MONTH',
  label: string,
  dense = false,
): AnalysisGroup => ({
  type: 'DATE_HISTOGRAM',
  field: name,
  alias,
  unit,
  label,
  ...(dense ? { dense } : {}),
});

function analysis(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'chart'> &
    Partial<AnalysisViewConfig>,
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

const bind = (globalField: string, panelField: string): PanelBinding => ({
  globalField,
  panelField,
});

/** 板上四个筛选接到订单分析定义的字段上。 */
const bindings = (): PanelBinding[] => [
  bind('date', 'firstEventTime'),
  bind('channel', 'state.channel'),
  bind('province', 'state.address.province'),
  bind('payment', 'state.payment.method'),
];

const at = (x: number, y: number, w: number, h: number): PanelLayout => ({
  x,
  y,
  w,
  h,
});

export type Tab = keyof typeof SHOWCASE_TABS;

function owned(
  tab: Tab,
  id: string,
  title: string,
  config: AnalysisViewConfig,
  layout: PanelLayout,
  click?: PanelClick,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    owned: { definitionId: RETAIL_ORDER_ANALYSIS, config },
    bindings: bindings(),
    layout,
    tab,
    ...(click ? { click } : {}),
  };
}

function saved(
  tab: Tab,
  id: string,
  title: string,
  instanceId: string,
  layout: PanelLayout,
  click?: PanelClick,
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    instanceId,
    bindings: bindings(),
    layout,
    tab,
    ...(click ? { click } : {}),
  };
}

function optionsOf(name: string) {
  const options = retailOrdersDefinition.fields.find(
    candidate => candidate.name === name,
  )?.options;
  if (!options) throw new Error(`The order definition declares no ${name}.`);
  return options;
}

const enumFilter = (
  name: string,
  label: string,
  path: string,
): DashboardField => ({
  name,
  label,
  kind: 'enum',
  options: optionsOf(path),
  multiple: true,
});

const FIELDS: DashboardField[] = [
  // 可选、没有默认值：每张图读它自己问题的时间范围（近 12 个完整月、只取完整
  // 周、上个月……）；设了下单时间，整块板都收窄到那一段。
  { name: 'date', label: '下单时间', kind: 'datetime' },
  enumFilter('channel', '渠道', 'state.channel'),
  { name: 'province', label: '省份', kind: 'string', multiple: true },
  // 点饼图的一块设进来的那一种支付方式。
  enumFilter('payment', '支付方式', 'state.payment.method'),
];

const byProvince: PanelClick = { kind: 'filter', filter: 'province' };
const byPayment: PanelClick = { kind: 'filter', filter: 'payment' };

// ---------------------------------------------------------------- 面板的配置

/** 上月 GMV 达成：已结束的整月，不拿月中「至今」比整月目标。 */
const lastMonthGauge = analysis({
  filter: and(
    leaf('firstEventTime', 'BETWEEN', { type: 'preset', preset: 'lastMonth' }),
  ),
  groups: [],
  metrics: [sum('gmv', GMV, 'GMV')],
  chart: {
    type: 'gauge',
    gauge: { metric: 'gmv', target: SHOWCASE_MONTH_TARGET },
  },
});

/** 近 25 个月的日 GMV，7 日均线与目标区间；截至昨日。 */
const dailyGmv = analysis({
  filter: and(
    leaf('firstEventTime', 'LTE', { type: 'preset', preset: 'yesterday' }),
  ),
  groups: [byDate('firstEventTime', 'day', 'DAY', '日期', true)],
  metrics: [sum('gmv', GMV, 'GMV')],
  sort: [{ alias: 'day', direction: 'ASC' }],
  limit: 1000,
  chart: {
    type: 'line',
    cartesian: {
      x: 'day',
      series: [{ metric: 'gmv' }],
      derived: [{ kind: 'moving-average', metric: 'gmv', window: 7 }],
      referenceBands: [
        {
          axis: 'left',
          from: SHOWCASE_DAY_TARGET.from,
          to: SHOWCASE_DAY_TARGET.to,
          label: '日目标',
        },
      ],
    },
    legend: 'top',
  },
});

/** 月 GMV（柱）与客单价（线），只取完整月。 */
const monthlyGmvAov = analysis({
  filter: and(THROUGH_LAST_MONTH('firstEventTime')),
  groups: [byDate('firstEventTime', 'month', 'MONTH', '月份')],
  metrics: [
    sum('gmv', GMV, 'GMV'),
    count('orders', '订单数'),
    ratio('aov', '客单价', 'gmv', 'orders', { style: 'currency' }),
  ],
  sort: [{ alias: 'month', direction: 'ASC' }],
  chart: {
    type: 'combo',
    cartesian: {
      x: 'month',
      series: [
        { metric: 'gmv', type: 'bar', axis: 'left' },
        { metric: 'aov', type: 'line', axis: 'right' },
      ],
      yAxis: { right: { label: '客单价', min: 150 } },
    },
    legend: 'top',
  },
});

/** 新客与老客的 GMV 占比，百分比堆叠，只取完整月。 */
const newReturningShare = analysis({
  filter: and(THROUGH_LAST_MONTH('firstEventTime')),
  groups: [
    byDate('firstEventTime', 'month', 'MONTH', '月份'),
    byTerms('state.buyer.isNewBuyer', 'isNew', '新客'),
  ],
  metrics: [sum('gmv', GMV, 'GMV')],
  sort: [{ alias: 'month', direction: 'ASC' }],
  limit: 1000,
  chart: {
    type: 'area',
    cartesian: {
      x: 'month',
      splitBy: 'isNew',
      series: [{ metric: 'gmv', stack: 'buyers' }],
      percentStack: true,
    },
    legend: 'top',
  },
});

/** 各渠道每周 GMV：52 个完整的周（周一到周日）。 */
const weeklyRiver = analysis({
  filter: and(FIFTY_TWO_WEEKS('firstEventTime')),
  groups: [
    byDate('firstEventTime', 'week', 'WEEK', '周'),
    byTerms('state.channel', 'channel'),
  ],
  metrics: [sum('gmv', GMV, 'GMV')],
  sort: [{ alias: 'week', direction: 'ASC' }],
  limit: 1000,
  chart: {
    type: 'themeRiver',
    themeRiver: { x: 'week', splitBy: 'channel', value: 'gmv' },
  },
});

/** 近 12 个完整月的每日 GMV（日历）。 */
const dailyCalendar = analysis({
  filter: and(TWELVE_FULL_MONTHS('firstEventTime')),
  groups: [byDate('firstEventTime', 'day', 'DAY', '日期')],
  metrics: [sum('gmv', GMV, 'GMV')],
  sort: [{ alias: 'day', direction: 'ASC' }],
  limit: 1000,
  chart: { type: 'calendar', calendar: { date: 'day', value: 'gmv' } },
});

/**
 * 一件商品每周的成交单价：开＝周内第一行、收＝最后一行，高与低是那一周成交
 * 单价的最高与最低。成交单价是单件的价（牌价减单品直降），与一单买几件无关；
 * 大促那几周直降加深，K 线往下走。
 */
const skuWeeklyPrice = analysis({
  filter: and(FIFTY_TWO_WEEKS('firstEventTime'), PAID),
  elements: [
    {
      path: 'state.items',
      filter: and(leaf('state.items.skuId', 'IN', [SHOWCASE_SKU])),
    },
  ],
  groups: [byDate('state.items.placedAt', 'week', 'WEEK', '下单周')],
  metrics: [
    {
      alias: 'open',
      type: 'FIRST',
      field: 'state.items.salePrice',
      orderBy: 'state.items.placedAt',
      label: '周内首单价',
    },
    {
      alias: 'high',
      type: 'NUMERIC',
      function: 'MAX',
      expression: field('state.items.salePrice'),
      label: '周内最高价',
    },
    {
      alias: 'low',
      type: 'NUMERIC',
      function: 'MIN',
      expression: field('state.items.salePrice'),
      label: '周内最低价',
    },
    {
      alias: 'close',
      type: 'LAST',
      field: 'state.items.salePrice',
      orderBy: 'state.items.placedAt',
      label: '周内末单价',
    },
  ] as [AnalysisMetric, ...AnalysisMetric[]],
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
});

/** 上月净销售额（实付 − 已退）由各渠道一段段累加。 */
const lastMonthNetWaterfall = analysis({
  filter: and(
    leaf('firstEventTime', 'BETWEEN', { type: 'preset', preset: 'lastMonth' }),
  ),
  groups: [byTerms('state.channel', 'channel')],
  metrics: [
    {
      alias: 'net',
      type: 'NUMERIC',
      function: 'SUM',
      label: '净销售额',
      expression: {
        type: 'BINARY',
        operator: 'SUBTRACT',
        left: field('state.amounts.paidAmount'),
        right: field('state.amounts.refundedAmount'),
      },
    },
  ],
  sort: [{ alias: 'net', direction: 'DESC' }],
  chart: {
    type: 'waterfall',
    waterfall: { x: 'channel', value: 'net', total: true },
  },
});

/** 渠道的比率与比率：每根轴都是一个比率，轮廓不再只是大小。 */
const channelRatios = analysis({
  filter: and(TWELVE_FULL_MONTHS('firstEventTime')),
  groups: [byTerms('state.channel', 'channel')],
  metrics: [
    count('orders', '订单数'),
    count('paidOrders', '已付款', [PAID]),
    count('returning', '老客单', [leaf('state.buyer.isNewBuyer', 'EQ', false)]),
    count('cancelled', '取消', [
      leaf('state.cancelReason', 'IS_NOT_NULL', null),
    ]),
    sum('paid', 'state.amounts.paidAmount', '实付'),
    sum('refunded', 'state.amounts.refundedAmount', '已退'),
    sum('list', 'state.amounts.listAmount', '原价合计'),
    sum('itemDiscount', 'state.amounts.itemDiscount', '单品直降'),
    ratio('refundRate', '退款率', 'refunded', 'paid'),
    ratio('discountRate', '直降占比', 'itemDiscount', 'list'),
    ratio('returningRate', '老客占比', 'returning', 'orders'),
    ratio('cancelRate', '取消率', 'cancelled', 'orders'),
  ],
  chart: {
    type: 'radar',
    radar: {
      category: 'channel',
      metrics: ['refundRate', 'discountRate', 'returningRate', 'cancelRate'],
    },
  },
});

/** 5 个渠道在单量、GMV、退款率、客单价上的对照，一条线一个渠道。 */
const channelParallel = analysis({
  filter: and(TWELVE_FULL_MONTHS('firstEventTime')),
  groups: [byTerms('state.channel', 'channel')],
  metrics: [
    count('orders', '订单数'),
    sum('gmv', GMV, 'GMV'),
    sum('paid', 'state.amounts.paidAmount', '实付'),
    sum('refunded', 'state.amounts.refundedAmount', '已退'),
    ratio('refundRate', '退款率', 'refunded', 'paid'),
    ratio('aov', '客单价', 'gmv', 'orders', { style: 'currency' }),
  ],
  sort: [{ alias: 'gmv', direction: 'DESC' }],
  chart: {
    type: 'parallel',
    parallel: {
      category: 'channel',
      metrics: ['orders', 'gmv', 'refundRate', 'aov'],
    },
  },
});

/** 各省 GMV（近 12 个完整月），省级地图。 */
const provinceMap = analysis({
  filter: and(TWELVE_FULL_MONTHS('firstEventTime')),
  groups: [byTerms('state.address.province', 'province')],
  metrics: [sum('gmv', GMV, 'GMV')],
  sort: [{ alias: 'gmv', direction: 'DESC' }],
  chart: {
    type: 'map',
    map: { region: 'province', value: 'gmv', map: SHOWCASE_MAP },
  },
});

// ---------------------------------------------------------------- 板子

/** 引用的已存分析（分析工作台与图型陈列里已经验证过的问题）。 */
export const SHOWCASE_SAVED = {
  metric: 'a01-gmv-mtd',
  pie: 'a17-payment-methods',
  treemap: 'a04-category-treemap',
  sunburst: CHART_VIEW_IDS.sunburst,
  tree: CHART_VIEW_IDS.tree,
  sankey: CHART_VIEW_IDS.sankey,
  bar: 'a06-provinces',
  boxplot: CHART_VIEW_IDS.boxplot,
  scatter: 'a14-discount-refund',
  heatmap: 'a10-weekday-hour',
  funnel: 'a09-funnel',
} as const;

/** 图型陈列里被这块板引用的那几张，随板子的存储一起放进去。 */
export const SHOWCASE_VIEWS: ViewInstance[] = CHART_VIEWS.filter(view =>
  (Object.values(SHOWCASE_SAVED) as string[]).includes(view.id),
);

const skuTitle = SKU_BY_ID.get(SHOWCASE_SKU)!.title;

export function showcaseConfig(): DashboardViewConfig {
  return {
    refresh: { interval: null },
    kind: 'dashboard',
    columns: 24,
    width: 'fixed',
    fixed: and(),
    tabs: Object.entries(SHOWCASE_TABS).map(([id, title]) => ({ id, title })),
    fields: FIELDS,
    panels: [
      // 走势：先回答「这个月怎么样」，再往回看长期、结构与一件商品的价。
      saved(
        'trend',
        'metric',
        '本月至今的 GMV 比上月同期多还是少？',
        SHOWCASE_SAVED.metric,
        at(0, 0, 8, 4),
      ),
      owned(
        'trend',
        'gauge',
        `上月 GMV 完成月目标（¥${SHOWCASE_MONTH_TARGET / 10_000} 万）了吗？`,
        lastMonthGauge,
        at(8, 0, 8, 4),
      ),
      owned(
        'trend',
        'combo',
        '每个完整月的 GMV 与客单价怎么变？',
        monthlyGmvAov,
        at(16, 0, 8, 4),
      ),
      owned(
        'trend',
        'line',
        '近 25 个月的日 GMV 怎么走，哪些天出了目标区间？',
        dailyGmv,
        at(0, 4, 24, 5),
      ),
      owned(
        'trend',
        'area',
        '新客贡献的 GMV 占比在变大还是变小？',
        newReturningShare,
        at(0, 9, 12, 5),
      ),
      owned(
        'trend',
        'river',
        '各渠道每周的 GMV 此消彼长（52 个完整周）',
        weeklyRiver,
        at(12, 9, 12, 5),
      ),
      owned(
        'trend',
        'calendar',
        '近 12 个完整月里，哪些日子卖得最多？',
        dailyCalendar,
        at(0, 14, 24, 5),
      ),
      owned(
        'trend',
        'candlestick',
        `「${skuTitle}」每周成交单价怎么走？（52 个完整周，红涨绿跌）`,
        skuWeeklyPrice,
        at(0, 19, 24, 5),
      ),
      // 构成：钱从哪些付款方式、品类、渠道来。
      saved(
        'mix',
        'pie',
        '买家用什么付款？（全部 25 个月，点一块筛选整板）',
        SHOWCASE_SAVED.pie,
        at(0, 0, 8, 5),
        byPayment,
      ),
      owned(
        'mix',
        'waterfall',
        '上月的净销售额是哪几个渠道凑成的？',
        lastMonthNetWaterfall,
        at(8, 0, 16, 5),
      ),
      saved(
        'mix',
        'treemap',
        '近 12 个月的实付由哪些品类构成？',
        SHOWCASE_SAVED.treemap,
        at(0, 5, 12, 5),
      ),
      saved(
        'mix',
        'sunburst',
        '每个品类里，哪些子类撑起了实付？（近 12 个月）',
        SHOWCASE_SAVED.sunburst,
        at(12, 5, 12, 5),
      ),
      saved(
        'mix',
        'tree',
        '品类 → 子类，各自是多少钱？（近 12 个月）',
        SHOWCASE_SAVED.tree,
        at(0, 10, 12, 6),
      ),
      saved(
        'mix',
        'sankey',
        '每个渠道的钱流向哪种支付方式？（近 3 个月）',
        SHOWCASE_SAVED.sankey,
        at(12, 10, 12, 6),
      ),
      // 分布与关系：排名、离散、相关、时段与多指标轮廓。
      saved(
        'spread',
        'bar',
        '哪些省份买得最多？（全部 25 个月，点一根柱筛选整板）',
        SHOWCASE_SAVED.bar,
        at(0, 0, 12, 5),
        byProvince,
      ),
      saved(
        'spread',
        'boxplot',
        '各仓付款到发货要多久，拖得最久的有多久？（近 3 个月）',
        SHOWCASE_SAVED.boxplot,
        at(12, 0, 12, 5),
      ),
      saved(
        'spread',
        'scatter',
        '直播间优惠给得越多，退得也越多吗？（近 12 个月，每天一点）',
        SHOWCASE_SAVED.scatter,
        at(0, 5, 12, 5),
      ),
      saved(
        'spread',
        'heatmap',
        '买家在星期几、几点下单？（全部 25 个月）',
        SHOWCASE_SAVED.heatmap,
        at(12, 5, 12, 5),
      ),
      owned(
        'spread',
        'radar',
        '各渠道的经营轮廓：退款、直降、老客、取消各占几成？（近 12 个完整月）',
        channelRatios,
        at(0, 10, 12, 6),
      ),
      owned(
        'spread',
        'parallel',
        '5 个渠道在单量、GMV、退款率与客单价上怎么比？（近 12 个完整月）',
        channelParallel,
        at(12, 10, 12, 6),
      ),
      // 地域与转化。
      owned(
        'region',
        'map',
        '哪些省份的 GMV 最高？（近 12 个完整月，点一个省筛选整板）',
        provinceMap,
        at(0, 0, 14, 7),
        byProvince,
      ),
      saved(
        'region',
        'funnel',
        '从下单到完成，哪一步流失最多？（全部 25 个月）',
        SHOWCASE_SAVED.funnel,
        at(14, 0, 10, 7),
      ),
    ],
  };
}

/** One panel of the board as a story reads it: where, what it asks, what it draws. */
export interface ShowcasePanel {
  id: string;
  tab: Tab;
  title: string;
  type: ChartType;
  /** The saved analysis the panel refers to; none when the board owns it. */
  instanceId?: string;
}

/** The board's panels, each with the chart type its analysis draws. */
export function showcasePanels(): ShowcasePanel[] {
  const views = [...ANALYSIS_VIEWS, ...CHART_VIEWS];
  return showcaseConfig().panels.flatMap(panel => {
    if (panel.kind !== 'view') return [];
    const config = panel.owned
      ? panel.owned.config
      : views.find(view => view.id === panel.instanceId)?.config;
    if (config?.kind !== 'analysis')
      throw new Error(`${panel.id} draws no analysis.`);
    return [
      {
        id: panel.id,
        tab: panel.tab as Tab,
        title: panel.title ?? panel.id,
        type: config.chart.type,
        ...(panel.instanceId ? { instanceId: panel.instanceId } : {}),
      },
    ];
  });
}
