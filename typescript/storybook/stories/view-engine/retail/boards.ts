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
 * 栖木生活的仪表盘（docs/scenarios.md 4.1、4.2）：运营日报、销售复盘、履约与
 * 售后，外加会员详情页嵌的那块板。
 *
 * 定义与已存视图是第 3 批的（`definitions.ts`、`views.ts`）：板上的面板尽量
 * 引用分析师在分析工作台里存下的分析（A-02、A-04……）、订单与售后工作台的
 * 系统视图（「发货超时」「全部订单」……），与那几个工作台读同一个口径。只有
 * 板子自己才有的——指标卡、逐时对比、月度指标表——是板内自有的分析（`owned`，
 * D22 C），随板子保存。
 *
 * 日报的「日期」默认是**昨日**：带走势的指标卡锚到板上所选的那一天（D39），
 * 读它、与前一日比，走势是以它为终点的近 30 天；其余面板收窄到那一天。几处
 * 不照 4.1 字面的地方写在用到的地方，也记在 scenarios.md 4.2：「买家数」换成
 * 「新客数」，「退款率」换成按退款日的「售后退款」金额。
 * ------------------------------------------------------------------------ */

import {
  systemInstanceId,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DashboardDefinition,
  type DerivedFormat,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterNode,
  type PanelBinding,
  type PanelClick,
  type PanelLayout,
  type PanelPresentation,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { MEMBER_OPTIONS } from './source.js';
import {
  AFTER_SALE_WORKBENCH_VIEWS,
  ANALYSIS_VIEWS,
  MEMBER_ANALYSIS_VIEWS,
  ORDER_WORKBENCH_VIEWS,
  RETAIL_AFTER_SALES,
  RETAIL_ORDERS,
  RETAIL_ORDER_ANALYSIS,
  RETAIL_ORDER_EVENTS,
  RETAIL_WAYBILLS,
  retailAfterSalesDefinition,
  retailMembersDefinition,
  retailOrderAnalysisDefinition,
  retailOrderEventsDefinition,
  retailOrdersDefinition,
  retailWaybillsDefinition,
} from './views.js';

// ---------------------------------------------------------------- 小工具

const and = (...children: FilterNode[]) => ({
  op: 'and' as const,
  children,
});

const PAID: FilterNode = {
  field: 'state.timing.paidAt',
  operator: 'IS_NOT_NULL',
  value: null,
};
const NOT_CANCELLED: FilterNode = {
  field: 'state.cancelReason',
  operator: 'IS_NULL',
  value: null,
};
const ON_TIME: FilterNode = {
  field: 'state.shipSlaBreached',
  operator: 'EQ',
  value: false,
};

const YESTERDAY = (field: string): FilterNode => ({
  field,
  operator: 'BETWEEN',
  value: { type: 'preset', preset: 'yesterday' },
});

/** 近 N 天：今天与它之前的 N − 1 天，按整天算（D39）。 */
const RECENT_DAYS = (field: string, amount: number): FilterNode => ({
  field,
  operator: 'BETWEEN',
  value: { type: 'relative', amount, unit: 'day' },
});

const sum = (
  alias: string,
  field: string,
  label?: string,
  filter?: FilterNode[],
): AnalysisMetric => ({
  type: 'NUMERIC',
  alias,
  function: 'SUM',
  expression: { type: 'FIELD', field },
  ...(label ? { label } : {}),
  ...(filter ? { filter: and(...filter) } : {}),
});

const avg = (
  alias: string,
  field: string,
  label?: string,
  filter?: FilterNode[],
): AnalysisMetric => ({
  type: 'NUMERIC',
  alias,
  function: 'AVG',
  expression: { type: 'FIELD', field },
  ...(label ? { label } : {}),
  ...(filter ? { filter: and(...filter) } : {}),
});

const count = (
  alias: string,
  label?: string,
  filter?: FilterNode[],
): AnalysisMetric => ({
  type: 'COUNT',
  alias,
  ...(label ? { label } : {}),
  ...(filter ? { filter: and(...filter) } : {}),
});

const distinct = (
  alias: string,
  field: string,
  label: string,
): AnalysisMetric => ({
  type: 'DISTINCT_COUNT',
  alias,
  label,
  expression: { type: 'FIELD', field },
});

/** 一个指标除以另一个，读作百分比或金额（D38）。 */
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

const byDay = (field: string): AnalysisGroup => ({
  type: 'DATE_HISTOGRAM',
  field,
  alias: 'day',
  unit: 'DAY',
  dense: true,
});

const byMonth = (field: string): AnalysisGroup => ({
  type: 'DATE_HISTOGRAM',
  field,
  alias: 'month',
  unit: 'MONTH',
});

/** 一个分析视图的配置，其余成员取常用的缺省。 */
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

const at = (x: number, y: number, w: number, h: number): PanelLayout => ({
  x,
  y,
  w,
  h,
});

type PanelExtra = {
  tab?: string;
  click?: PanelClick;
  presentation?: PanelPresentation;
};

/** 板子自己的一个分析面板（D22 C），默认在订单的分析定义上。 */
function owned(
  id: string,
  title: string,
  config: AnalysisViewConfig,
  layout: PanelLayout,
  bindings: PanelBinding[],
  extra: PanelExtra & { definitionId?: string } = {},
): DashboardPanel {
  const { definitionId = RETAIL_ORDER_ANALYSIS, ...rest } = extra;
  return {
    id,
    kind: 'view',
    title,
    owned: { definitionId, config },
    bindings,
    layout,
    ...rest,
  };
}

/** 一个引用已存视图（第 3 批的共享视图或系统视图）的面板。 */
function saved(
  id: string,
  title: string,
  instanceId: string,
  layout: PanelLayout,
  bindings: PanelBinding[],
  extra: PanelExtra = {},
): DashboardPanel {
  return { id, kind: 'view', title, instanceId, bindings, layout, ...extra };
}

/**
 * 一枚枚举筛选的选项：取订单定义里那个字段声明的那一组，筛选条（与交叉筛选
 * 设进来的值）读的是标签而不是代码。筛选不列自己的选项时，交叉筛选设进来的
 * 值在条上读成代码（「LIVE」）。
 */
function optionsOf(field: string) {
  const options = retailOrdersDefinition.fields.find(
    candidate => candidate.name === field,
  )?.options;
  if (!options) throw new Error(`The order definition declares no ${field}.`);
  return options;
}

const enumFilter = (
  name: string,
  label: string,
  field: string,
): DashboardField => ({
  name,
  label,
  kind: 'enum',
  options: optionsOf(field),
  multiple: true,
});

// ---------------------------------------------------------------- 引用的视图

/** 订单工作台的「发货超时」：待发货、付款 48 小时仍未发出、不是预售，最早付款的在前。 */
export const OVERDUE_VIEW = systemInstanceId(RETAIL_ORDERS, 'ship-overdue');
/** 订单工作台的「全部订单」：点一组去明细，带着这一组作条件。 */
export const ORDER_LIST_VIEW = systemInstanceId(RETAIL_ORDERS, 'all');
/** 售后工作台的「全部售后」。 */
export const AFTER_SALE_VIEW = systemInstanceId(RETAIL_AFTER_SALES, 'all');
/** 订单事件流的「订单历史」：模板，按订单号填写，按版本排序。 */
export const ORDER_HISTORY_VIEW = systemInstanceId(
  RETAIL_ORDER_EVENTS,
  'history',
);
/** 分析师存下的分析（分析工作台的视图列表里也有）。 */
const ANALYST = {
  monthlyGmvAov: 'a02-monthly-gmv-aov',
  channelChange: 'a03-channel-change',
  categoryTreemap: 'a04-category-treemap',
  channelMix: 'a05-channel-mix',
  provinces: 'a06-provinces',
  tierChannel: 'a06-tier-channel',
  refundOutliers: 'a07-refund-outliers',
  topBuyers: 'a08-top-buyers',
  activities: 'a11-activities',
  weeklySla: 'a12-weekly-sla',
  warehouseHours: 'a12-warehouse-hours',
  newReturning: 'a15-new-returning',
  priceBands: 'a16-price-bands',
  repurchase: 'a18-repurchase',
} as const;

/** 一张单的商品行：展开 `items`，一行一个商品（订单详情页）。板子这一批自己存的。 */
export const ORDER_LINES_VIEW = 'retail-order-lines';

const orderLinesView: ViewInstance = {
  id: ORDER_LINES_VIEW,
  definitionId: RETAIL_ORDER_ANALYSIS,
  title: '订单商品行',
  scope: 'shared',
  revision: '1',
  config: analysis({
    elements: [{ path: 'state.items' }],
    groups: [
      {
        type: 'TERMS',
        field: 'state.items.title',
        alias: 'title',
        label: '商品',
      },
    ],
    metrics: [
      sum('qty', 'state.items.qty', '件数'),
      sum('pay', 'state.items.payAmount', '实付'),
      sum('refunded', 'state.items.refundedAmount', '已退'),
    ],
    sort: [{ alias: 'pay', direction: 'DESC' }],
    limit: 10,
    layout: 'table',
    table: { columns: [], totals: true },
    chart: {
      type: 'bar',
      cartesian: { x: 'title', series: [{ metric: 'pay' }] },
    },
  }),
};

// ---------------------------------------------------------------- 运营日报

export const OPS_DAILY = 'retail-ops-daily';

/**
 * 日报默认看的那一天：昨日（6.3）。带走势的卡锚到它（D39），其余面板收窄到
 * 它；读者可以改成「前天」或日历上的任一天，卡片跟着锚过去。
 */
export const DAILY_DAY = { type: 'preset', preset: 'yesterday' } as const;

const DAILY_FIELDS: DashboardField[] = [
  {
    name: 'date',
    label: '日期',
    kind: 'datetime',
    default: DAILY_DAY,
    required: true,
    // 日报读的是一天：日历上的一天，或今天、昨天、前天。
    oneDay: true,
  },
  enumFilter('channel', '渠道', 'state.channel'),
  enumFilter('shop', '店铺', 'state.shopId'),
  // 搜索一类（D36）：只接明细面板，编译成 Wow 的 SEARCH（订单号、买家昵称、
  // 商品名，外加买家留言）。搭建界面的「添加筛选」还不列这一类，所以它在
  // 配置里声明。
  { name: 'q', label: '搜索订单', kind: 'search' },
];

/**
 * 订单面板接日报的三枚筛选；`date` 接到 `dateField`，`null` 不接——读此刻状
 * 态的队列（超时明细）与指标自带日期的面板（今日与昨日）不跟着日期走。
 */
const orderBindings = (dateField: string | null = 'firstEventTime') => [
  ...(dateField === null ? [] : [bind('date', dateField)]),
  bind('channel', 'state.channel'),
  bind('shop', 'state.shopId'),
];

/**
 * 一张带走势的指标卡：按日分桶、近 30 天，读作最后一个过完的日与前一日之比
 * （`last`）。板上的日期选了一天，卡片锚到那一天（D39）：近 30 天以它为终点，
 * 读它、与前一日比。`metric` 是卡上的那个数；比值（客单价、转化率、及时率）
 * 连同它的两个操作数一起给，每一天读那一天的两个和相除（D38）。
 */
function card(
  metrics: [AnalysisMetric, ...AnalysisMetric[]],
  options: {
    timeField?: string;
    lowerIsBetter?: boolean;
    target?: number;
  } = {},
): AnalysisViewConfig {
  const time = options.timeField ?? 'firstEventTime';
  const metric = metrics[metrics.length - 1];
  return analysis({
    filter: and(RECENT_DAYS(time, 30)),
    groups: [byDay(time)],
    metrics,
    sort: [{ alias: 'day', direction: 'ASC' }],
    limit: 62,
    chart: {
      type: 'metric',
      metric: {
        metric: metric.alias,
        trend: { x: 'day' },
        ...(options.lowerIsBetter ? { lowerIsBetter: true } : {}),
        ...(options.target === undefined ? {} : { target: options.target }),
      },
    },
  });
}

/** 日报上售后退款的商品榜。 */
export const REFUND_SKUS = '售后退款最多的 5 个商品（近 30 天）';

/** 日报上近 30 天日 GMV 那块面板的标题（能框选）。 */
export const DAILY_TREND = '近 30 天的日 GMV';

/** 日报上八张卡的标题，左上到右下。 */
export const DAILY_CARDS = [
  'GMV',
  '实付金额',
  '订单数（单）',
  '新客数（人）',
  '客单价',
  '支付转化率',
  '售后退款',
  '发货及时率',
] as const;

function opsDailyConfig(): DashboardViewConfig {
  // 第一眼（6.3）：八张带走势的卡两行，逐时 GMV 紧跟在下面，1280×800 不滚动
  // 就看得到它的开头。
  const layouts = DAILY_CARDS.map((_, index) =>
    at((index % 4) * 6, Math.floor(index / 4) * 3, 6, 3),
  );
  const [gmv, paid, orders, buyers, aov, conversion, refund, onTime] =
    DAILY_CARDS;
  return {
    refresh: { interval: null },
    kind: 'dashboard',
    columns: 24,
    width: 'full',
    fixed: and(),
    tabs: [],
    fields: DAILY_FIELDS,
    panels: [
      owned(
        'gmv',
        gmv,
        card([sum('gmv', 'state.amounts.payableAmount', 'GMV')]),
        layouts[0],
        orderBindings(),
      ),
      owned(
        'paid',
        paid,
        card([sum('paid', 'state.amounts.paidAmount', '实付金额')]),
        layouts[1],
        orderBindings(),
      ),
      owned(
        'orders',
        orders,
        card([count('orders', '订单数')]),
        layouts[2],
        orderBindings(),
      ),
      // 新客数：买家第一张付款子单上 `isNewBuyer` 为真，按天数它就是那天来的新
      // 客，能相加，才画得出走势；买家数（去重计数）在销售复盘的月度指标里。
      owned(
        'new-buyers',
        buyers,
        card([
          count('newBuyers', '新客数', [
            { field: 'state.buyer.isNewBuyer', operator: 'EQ', value: true },
          ]),
        ]),
        layouts[3],
        orderBindings(),
      ),
      // 客单价 = GMV ÷ 订单数：每一天是那一天的两个和相除（D38），读作金额，
      // 币种随 GMV。
      owned(
        'aov',
        aov,
        card([
          sum('aovGmv', 'state.amounts.payableAmount', 'GMV'),
          count('aovOrders', '订单数'),
          ratio('aov', '客单价', 'aovGmv', 'aovOrders', { style: 'currency' }),
        ]),
        layouts[4],
        orderBindings(),
      ),
      // 支付转化率：那一天下的单里付了款的比例。
      owned(
        'conversion',
        conversion,
        card([
          count('placed', '下单'),
          count('paidOrders', '付款', [PAID]),
          ratio('conversion', '支付转化率', 'paidOrders', 'placed'),
        ]),
        layouts[5],
        orderBindings(),
      ),
      // 退款按下单日算，最近几天的单还没来得及退，会一路「变好」；日报按退款
      // 日读退款金额，越低越好。
      owned(
        'refund',
        refund,
        card([sum('refunded', 'state.refundedAmount', '售后退款')], {
          timeField: 'state.refundedAt',
          lowerIsBetter: true,
        }),
        layouts[6],
        [
          bind('date', 'state.refundedAt'),
          bind('channel', 'state.channel'),
          bind('shop', 'state.shopId'),
        ],
        { definitionId: RETAIL_AFTER_SALES },
      ),
      // 发货及时率按发货期限算：期限（付款 + 48 小时）落在那一天、未取消的单里，
      // 按时发出的比例（scenarios.md 2.6 A7）；目标 95%，每天一个目标。
      owned(
        'on-time',
        onTime,
        card(
          [
            count('due', '到期', [NOT_CANCELLED]),
            count('onTime', '按时发出', [NOT_CANCELLED, ON_TIME]),
            ratio('rate', '发货及时率', 'onTime', 'due'),
          ],
          { timeField: 'state.timing.shipDueAt', target: 0.95 },
        ),
        layouts[7],
        orderBindings('state.timing.shipDueAt'),
      ),
      owned(
        'hourly',
        '今日与昨日的逐时 GMV',
        analysis({
          groups: [
            {
              type: 'DATE_PART',
              field: 'firstEventTime',
              alias: 'hour',
              part: 'HOUR_OF_DAY',
            },
          ],
          metrics: [
            sum('yesterday', 'state.amounts.payableAmount', '昨日', [
              YESTERDAY('firstEventTime'),
            ]),
            sum('today', 'state.amounts.payableAmount', '今日', [
              {
                field: 'firstEventTime',
                operator: 'BETWEEN',
                value: { type: 'preset', preset: 'today' },
              },
            ]),
          ],
          sort: [{ alias: 'hour', direction: 'ASC' }],
          limit: 24,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'hour',
              series: [{ metric: 'yesterday' }, { metric: 'today' }],
              // 今天 10 点以后还没有发生：那里没有柱，而不是一根 0。
              missing: 'gap',
            },
            legend: 'top',
            // 24 根柱各写一个数挤成一片；读数在提示框与读屏表里。
            labels: false,
          },
        }),
        at(0, 6, 14, 4),
        // 「今日」「昨日」写在两个指标自己的条件里，与板上选的那天无关。
        orderBindings(null),
      ),
      owned(
        'by-channel',
        '渠道分布',
        analysis({
          groups: [
            {
              type: 'TERMS',
              field: 'state.channel',
              alias: 'channel',
              label: '渠道',
            },
          ],
          metrics: [sum('gmv', 'state.amounts.payableAmount', 'GMV')],
          sort: [{ alias: 'gmv', direction: 'DESC' }],
          limit: 10,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'channel',
              series: [{ metric: 'gmv' }],
              orientation: 'horizontal',
            },
            legend: 'none',
          },
        }),
        at(14, 6, 10, 4),
        orderBindings(),
        // 点一个渠道：整块板筛到这个渠道（交叉筛选，D22 I）。
        { click: { kind: 'filter', filter: 'channel' } },
      ),
      saved(
        'overdue',
        '付款超过 48 小时仍未发货',
        OVERDUE_VIEW,
        at(0, 10, 14, 5),
        // 此刻还在等发货的单，与下单是哪天无关：不接「日期」。
        [...orderBindings(null), bind('q', 'keyword')],
      ),
      owned(
        'refund-skus',
        REFUND_SKUS,
        analysis({
          // 一天的退款太少，排不出谁最多：这张榜看近 30 天，不接「日期」。
          filter: and(RECENT_DAYS('state.refundedAt', 30)),
          groups: [
            {
              type: 'TERMS',
              field: 'state.title',
              alias: 'sku',
              label: '商品',
            },
          ],
          metrics: [
            sum('refunded', 'state.refundedAmount', '退款金额'),
            count('cases', '售后单数'),
          ],
          sort: [{ alias: 'refunded', direction: 'DESC' }],
          limit: 5,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'sku',
              series: [{ metric: 'refunded' }],
              orientation: 'horizontal',
            },
            legend: 'none',
          },
        }),
        at(14, 10, 10, 5),
        [bind('channel', 'state.channel'), bind('shop', 'state.shopId')],
        // 点一个商品：打开销售复盘的「品类」页，带上这块板的渠道（D23 Q17）；
        // 退款率在那边的「退款率最高的商品（近 3 个月）」里读——日报的「昨日」
        // 对三个月的退款率没有意义，所以不带日期。按退款率排要展开商品行，而
        // 展开了的分析没有可点的组，所以日报这里按售后单的商品排。
        {
          definitionId: RETAIL_AFTER_SALES,
          click: {
            kind: 'dashboard',
            instanceId: SALES_REVIEW,
            values: { channel: { filter: 'channel' } },
            // 落在「品类」页（D39）：退款率榜在那里。
            tab: 'category',
          },
        },
      ),
      // 近 30 天的日 GMV（批 C）：日报只看一天，这一块给出这一个月的来龙去脉，
      // 日均线与峰谷点标出不寻常的几天。横轴是日期，所以能框选：拖过几天弹出追问菜单，「查看这些记录」开出
      // 那几天的单。它有自己的 30 天，不接「日期」——接上了，日报的「昨日」会
      // 把它收成一根柱；所以框选的菜单里也没有「设为「日期」」。
      owned(
        'daily-gmv',
        DAILY_TREND,
        analysis({
          // 截至昨日（今天还没过完），最近的 30 天：按日倒序取 30 组，图上照
          // 时间先后画。
          filter: and({
            field: 'firstEventTime',
            operator: 'LTE',
            value: { type: 'preset', preset: 'yesterday' },
          }),
          groups: [byDay('firstEventTime')],
          metrics: [sum('gmv', 'state.amounts.payableAmount', 'GMV')],
          sort: [{ alias: 'day', direction: 'DESC' }],
          limit: 30,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'day',
              series: [{ metric: 'gmv' }],
              // 取的是最近 30 组，移动平均会缺开头几天（引擎不画）；日均线
              // 与峰谷点就够读出哪几天不寻常。
              referenceLines: [
                {
                  axis: 'left',
                  statistic: 'average',
                  metric: 'gmv',
                  label: '日均',
                },
              ],
              extremes: true,
            },
            legend: 'none',
          },
        }),
        at(0, 15, 24, 4),
        orderBindings(null),
      ),
      {
        id: 'runbook',
        kind: 'links',
        title: '值班手册',
        layout: at(0, 19, 24, 2),
        items: [
          {
            label: '发货超时处理流程',
            href: 'https://wiki.qimu.example/ops/ship-sla',
            description: '付款超过 48 小时未发：先催仓，再联系买家说明。',
          },
          {
            label: '华东（嘉兴）仓分拣线故障通报',
            href: 'https://wiki.qimu.example/incidents/2026-09-20-jiaxing-sorter',
            description:
              '09-20 起分拣线故障，09-21 18:00 修复，积压件人工重分。',
          },
          {
            label: '仓库值班表',
            href: 'https://wiki.qimu.example/ops/warehouse-roster',
          },
          {
            label: '售后话术',
            href: 'https://wiki.qimu.example/cs/after-sale-scripts',
          },
        ],
      },
    ],
  };
}

// ---------------------------------------------------------------- 销售复盘

export const SALES_REVIEW = 'retail-sales-review';

/** 销售复盘的四个标签页。 */
export const SALES_TABS = {
  overview: '概览',
  category: '品类',
  channel: '渠道与地域',
  customer: '客户',
} as const;

const SALES_FIELDS: DashboardField[] = [
  // 可选、没有默认值：复盘默认看各分析自己的范围，指标卡自己读最近一个过完的
  // 月；设了日期，整块板都收窄到那一段。按日看时设一天，指标卡锚到那一天（D39）。
  { name: 'date', label: '日期', kind: 'datetime' },
  enumFilter('channel', '渠道', 'state.channel'),
  enumFilter('level', '会员等级', 'state.buyer.level'),
];

const salesBindings = () => [
  bind('date', 'firstEventTime'),
  bind('channel', 'state.channel'),
  bind('level', 'state.buyer.level'),
];

/**
 * 月度复盘的一张卡：今年按月的走势，读作最近一个过完的月（8 月）较上一个月
 * ——9 月还没过完，拿 22 天比 31 天每个月初都会读成下跌。整板改成按日时，它读
 * 作昨日较前一日，与日报同一个口径。
 */
function monthCard(metric: AnalysisMetric): AnalysisViewConfig {
  return analysis({
    filter: and({
      field: 'firstEventTime',
      operator: 'GTE',
      value: { type: 'preset', preset: 'lastYear' },
    }),
    groups: [byMonth('firstEventTime')],
    metrics: [metric],
    sort: [{ alias: 'month', direction: 'ASC' }],
    limit: 400,
    chart: {
      type: 'metric',
      metric: { metric: metric.alias, trend: { x: 'month' } },
    },
  });
}

/** 销售复盘概览的三张卡。 */
export const SALES_CARDS = ['GMV', '实付金额', '订单数（单）'] as const;

function salesReviewConfig(): DashboardViewConfig {
  const overview = { tab: 'overview' };
  const category = { tab: 'category' };
  const channel = { tab: 'channel' };
  const customer = { tab: 'customer' };
  const [gmv, paid, orders] = SALES_CARDS;
  // 能相加的三个指标画成带走势的卡；主单数、买家数（去重计数）与客单价（平均）
  // 不能相加，引擎不给它们画走势，它们在下面的月度表里逐月读。
  const cards: [string, string, AnalysisViewConfig][] = [
    ['gmv', gmv, monthCard(sum('gmv', 'state.amounts.payableAmount', 'GMV'))],
    [
      'paid',
      paid,
      monthCard(sum('paid', 'state.amounts.paidAmount', '实付金额')),
    ],
    ['orders', orders, monthCard(count('orders', '订单数'))],
  ];
  return {
    refresh: { interval: null },
    kind: 'dashboard',
    columns: 24,
    width: 'fixed',
    fixed: and(),
    tabs: Object.entries(SALES_TABS).map(([id, title]) => ({ id, title })),
    fields: SALES_FIELDS,
    timeGrouping: { units: ['MONTH', 'WEEK', 'DAY'], default: 'MONTH' },
    panels: [
      ...cards.map(([id, title, config], index) =>
        owned(
          id,
          title,
          config,
          at(index * 8, 0, 8, 3),
          salesBindings(),
          overview,
        ),
      ),
      // A-02：月 GMV（柱）与客单价（线，右轴）。
      saved(
        'trend',
        '月 GMV 与客单价',
        ANALYST.monthlyGmvAov,
        at(0, 3, 14, 5),
        salesBindings(),
        overview,
      ),
      // A-03：本月至今较上月同期，按渠道。
      saved(
        'month-over-month',
        '本月 GMV 较上月同期（分渠道）',
        ANALYST.channelChange,
        at(14, 3, 10, 5),
        salesBindings(),
        overview,
      ),
      // 月度指标：今年逐月的订单、主单、买家与客单价——去重计数与平均在这里读。
      owned(
        'monthly',
        '月度指标（今年）',
        analysis({
          filter: and({
            field: 'firstEventTime',
            operator: 'GTE',
            value: { type: 'preset', preset: 'thisYear' },
          }),
          groups: [byMonth('firstEventTime')],
          metrics: [
            sum('gmv', 'state.amounts.payableAmount', 'GMV'),
            count('orders', '订单数'),
            distinct('parents', 'state.parentOrderNo', '主单数'),
            distinct('buyers', 'state.buyer.id', '买家数'),
            avg('aov', 'state.amounts.payableAmount', '客单价'),
          ],
          sort: [{ alias: 'month', direction: 'DESC' }],
          limit: 12,
          layout: 'table',
          table: { columns: [], totals: true },
          chart: {
            type: 'bar',
            cartesian: { x: 'month', series: [{ metric: 'gmv' }] },
          },
        }),
        at(0, 8, 24, 5),
        salesBindings(),
        overview,
      ),
      // A-04：品类构成（近 12 个月实付）。
      saved(
        'treemap',
        '品类构成（近 12 个月实付）',
        ANALYST.categoryTreemap,
        at(0, 0, 12, 5),
        salesBindings(),
        category,
      ),
      // A-16：成交单价分布。
      saved(
        'price-bands',
        '成交单价分布（每 100 元一档）',
        ANALYST.priceBands,
        at(12, 0, 12, 5),
        salesBindings(),
        category,
      ),
      // A-07：件数 ≥ 30 的商品里退款率最高的 10 个——浴巾排第一（A1）。展开了
      // 商品行的分析没有可点的组，所以这里不设点击；画成表格，逐行读。
      saved(
        'refund-skus',
        '退款率最高的商品（近 3 个月）',
        ANALYST.refundOutliers,
        at(0, 5, 24, 5),
        salesBindings(),
        { ...category, presentation: { layout: 'table' } },
      ),
      // A-05：渠道结构按月的份额；点一个渠道，整块板筛到它。
      saved(
        'channel-mix',
        '渠道结构（按月 GMV 占比）',
        ANALYST.channelMix,
        at(0, 0, 14, 5),
        salesBindings(),
        { ...channel, click: { kind: 'filter', filter: 'channel' } },
      ),
      // A-06：省份前 15；点一个省份，打开订单明细，条件就是这个省份。
      saved(
        'provinces',
        '省份 GMV 前 15',
        ANALYST.provinces,
        at(14, 0, 10, 5),
        salesBindings(),
        { ...channel, click: { kind: 'view', instanceId: ORDER_LIST_VIEW } },
      ),
      // A-06 另一张：城市等级 × 渠道，旧版小程序没报城市的那一组单列（A5）。
      saved(
        'tiers',
        '城市等级 × 渠道',
        ANALYST.tierChannel,
        at(0, 5, 12, 5),
        salesBindings(),
        channel,
      ),
      // A-11：各活动的客单价与优惠力度。
      saved(
        'activities',
        '各活动的客单价与优惠力度',
        ANALYST.activities,
        at(12, 5, 12, 5),
        salesBindings(),
        channel,
      ),
      // A-08：大客户 Top 20，带合计行。
      saved(
        'top-buyers',
        '大客户 Top 20',
        ANALYST.topBuyers,
        at(0, 0, 12, 6),
        salesBindings(),
        customer,
      ),
      // A-15：新客与老客的 GMV。
      saved(
        'new-vs-returning',
        '新客与老客的 GMV',
        ANALYST.newReturning,
        at(12, 0, 12, 6),
        salesBindings(),
        customer,
      ),
      // A-18：按首单月的复购率（会员数据；会员没有下单时间与渠道，日期与渠道
      // 接不上它，面板头会说「不受…影响」）。
      saved(
        'repurchase',
        '按首单月的复购率',
        ANALYST.repurchase,
        at(0, 6, 24, 4),
        [bind('level', 'state.level')],
        customer,
      ),
    ],
  };
}

// ---------------------------------------------------------------- 履约与售后

export const FULFILMENT = 'retail-fulfilment';

export const FULFILMENT_TABS = {
  fulfilment: '履约',
  afterSales: '售后',
} as const;

const FULFILMENT_FIELDS: DashboardField[] = [
  // 可选：不设就看各分析自己的范围——春节停运（A6）与双 11 的积压都在里面。
  { name: 'date', label: '日期', kind: 'datetime' },
  enumFilter('warehouse', '发货仓', 'state.warehouse'),
  { name: 'province', label: '省份', kind: 'string', multiple: true },
  enumFilter('channel', '渠道', 'state.channel'),
];

function fulfilmentConfig(): DashboardViewConfig {
  const ship = { tab: 'fulfilment' };
  const after = { tab: 'afterSales' };
  const orderWires = () => [
    bind('date', 'state.timing.paidAt'),
    bind('warehouse', 'state.warehouse'),
    bind('province', 'state.address.province'),
    bind('channel', 'state.channel'),
  ];
  const waybillWires = () => [
    bind('date', 'state.shippedAt'),
    bind('warehouse', 'state.warehouse'),
    bind('province', 'state.province'),
  ];
  const afterWires = () => [
    bind('date', 'state.requestedAt'),
    bind('channel', 'state.channel'),
  ];
  return {
    refresh: { interval: null },
    kind: 'dashboard',
    columns: 24,
    width: 'fixed',
    fixed: and(),
    tabs: Object.entries(FULFILMENT_TABS).map(([id, title]) => ({
      id,
      title,
    })),
    fields: FULFILMENT_FIELDS,
    panels: [
      // A-12：每周发货超时率，红线 5%。春节停运那几周冲过红线（A6）。
      saved(
        'breach-rate',
        '每周发货超时率',
        ANALYST.weeklySla,
        at(0, 0, 24, 5),
        orderWires(),
        ship,
      ),
      // A-12 另一张：各仓付款到发货的 P50 与 P90（≈）。
      saved(
        'ship-hours',
        '各仓付款到发货（P50 / P90）',
        ANALYST.warehouseHours,
        at(0, 5, 8, 4),
        orderWires(),
        ship,
      ),
      // A-13：承运商 × 周的签收时长（今年）。设「省份 = 广东省」，中通 7 月下旬
      // 那几格一下亮起来（A2 台风）。
      owned(
        'carrier-weeks',
        '承运商 × 周：平均签收时长（小时，今年）',
        analysis({
          filter: and({
            field: 'state.shippedAt',
            operator: 'GTE',
            value: { type: 'preset', preset: 'thisYear' },
          }),
          groups: [
            {
              type: 'TERMS',
              field: 'state.carrier',
              alias: 'carrier',
              label: '承运商',
            },
            {
              type: 'DATE_HISTOGRAM',
              field: 'state.shippedAt',
              alias: 'week',
              unit: 'WEEK',
            },
          ],
          metrics: [
            avg('hours', 'state.shipToSignHours', '平均签收时长（小时）'),
          ],
          sort: [{ alias: 'week', direction: 'ASC' }],
          limit: 1000,
          chart: {
            type: 'heatmap',
            heatmap: { x: 'week', y: 'carrier', value: 'hours' },
          },
        }),
        at(8, 5, 16, 4),
        waybillWires(),
        { ...ship, definitionId: RETAIL_WAYBILLS },
      ),
      saved(
        'overdue',
        '超时明细：付款超过 48 小时仍未发货',
        OVERDUE_VIEW,
        at(0, 9, 24, 5),
        orderWires(),
        ship,
      ),
      // 售后理由；点一个理由，打开售后单明细，条件就是这个理由。
      saved(
        'reasons',
        '售后理由构成',
        systemInstanceId(RETAIL_AFTER_SALES, 'by-reason'),
        at(0, 0, 10, 5),
        afterWires(),
        { ...after, click: { kind: 'view', instanceId: AFTER_SALE_VIEW } },
      ),
      saved(
        'refund-skus',
        '退款率最高的商品（近 3 个月）',
        ANALYST.refundOutliers,
        at(10, 0, 14, 5),
        orderWires(),
        { ...after, presentation: { layout: 'table' } },
      ),
      saved(
        'daily-refunds',
        '每日退款金额（近 30 天）',
        systemInstanceId(RETAIL_AFTER_SALES, 'daily-refunds'),
        at(0, 5, 24, 4),
        afterWires(),
        after,
      ),
      saved(
        'after-sales',
        '售后单明细',
        AFTER_SALE_VIEW,
        at(0, 9, 24, 5),
        afterWires(),
        after,
      ),
    ],
  };
}

// ---------------------------------------------------------------- 会员详情页的板

export const MEMBER_BOARD = 'retail-member-board';

/** 会员详情页那块板：买家由页面锁定，下单时间归读者。 */
function memberBoardConfig(): DashboardViewConfig {
  const orders = [
    bind('buyer', 'state.buyer.id'),
    bind('placed', 'firstEventTime'),
  ];
  return {
    refresh: { interval: null },
    kind: 'dashboard',
    columns: 24,
    width: 'full',
    fixed: and(),
    tabs: [],
    fields: [
      {
        name: 'buyer',
        label: '买家',
        kind: 'reference',
        remote: MEMBER_OPTIONS,
      },
      {
        name: 'placed',
        label: '下单时间',
        kind: 'datetime',
        default: { type: 'preset', preset: 'thisYear' },
      },
    ],
    panels: [
      owned(
        'spend',
        '实付（这段时间）',
        analysis({
          groups: [],
          metrics: [sum('paid', 'state.amounts.paidAmount', '实付金额')],
          limit: 1,
          chart: { type: 'metric', metric: { metric: 'paid' } },
        }),
        at(0, 0, 6, 2),
        orders,
      ),
      owned(
        'order-count',
        '订单数（单）',
        analysis({
          groups: [],
          metrics: [count('orders', '订单数')],
          limit: 1,
          chart: { type: 'metric', metric: { metric: 'orders' } },
        }),
        at(6, 0, 6, 2),
        orders,
      ),
      owned(
        'monthly',
        '每月实付',
        analysis({
          groups: [byMonth('firstEventTime')],
          metrics: [sum('paid', 'state.amounts.paidAmount', '实付金额')],
          sort: [{ alias: 'month', direction: 'ASC' }],
          limit: 60,
          chart: {
            type: 'bar',
            cartesian: { x: 'month', series: [{ metric: 'paid' }] },
            legend: 'none',
          },
        }),
        at(12, 0, 12, 4),
        orders,
      ),
      saved('orders', '他的订单', ORDER_LIST_VIEW, at(0, 4, 24, 5), orders),
      saved('after-sales', '他的售后', AFTER_SALE_VIEW, at(0, 9, 24, 4), [
        bind('buyer', 'state.buyerId'),
        bind('placed', 'state.requestedAt'),
      ]),
    ],
  };
}

// ---------------------------------------------------------------- 全部

export const RETAIL_BOARDS = 'retail-boards';

/** 仪表盘不拥有数据，定义只是它们在目录里的一项。 */
export const retailBoardsDefinition: DashboardDefinition = {
  id: RETAIL_BOARDS,
  title: '栖木生活 · 经营看板',
  kind: 'dashboard',
};

/** 板子与它引用的视图所在的定义：第 3 批的五个数据定义，加上仪表盘定义。 */
export const RETAIL_BOARD_DEFINITIONS = [
  retailOrdersDefinition,
  retailOrderAnalysisDefinition,
  retailAfterSalesDefinition,
  retailMembersDefinition,
  retailWaybillsDefinition,
  retailOrderEventsDefinition,
  retailBoardsDefinition,
];

function board(
  id: string,
  title: string,
  config: DashboardViewConfig,
): ViewInstance {
  return {
    id,
    definitionId: RETAIL_BOARDS,
    title,
    scope: 'shared',
    revision: '1',
    config,
  };
}

/** 运营组共享的四块板。 */
export const retailBoards: ViewInstance[] = [
  board(OPS_DAILY, '运营日报', opsDailyConfig()),
  board(SALES_REVIEW, '销售复盘（月度）', salesReviewConfig()),
  board(FULFILMENT, '履约与售后', fulfilmentConfig()),
  board(MEMBER_BOARD, '会员概览', memberBoardConfig()),
];

/**
 * 一台板子引擎的存储里起步就有的：第 3 批的共享与个人视图（订单、分析、会员、
 * 售后工作台），订单商品行，与四块板。
 */
export const retailInstances: ViewInstance[] = [
  ...ORDER_WORKBENCH_VIEWS,
  ...ANALYSIS_VIEWS,
  ...MEMBER_ANALYSIS_VIEWS,
  ...AFTER_SALE_WORKBENCH_VIEWS,
  orderLinesView,
  ...retailBoards,
];
