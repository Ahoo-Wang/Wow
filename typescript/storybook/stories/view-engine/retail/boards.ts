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
 * 栖木生活的仪表盘（docs/scenarios.md 4.1）：运营日报、销售复盘、履约与售后，
 * 外加会员详情页嵌的那块板，以及它们引用的已存视图。
 *
 * 板上的分析大多是板子自己的（`owned`，D22 C）——只为这块板而存在、随板子
 * 保存；记录面板与点击要去的目的地是运营组共享的已存视图，工作台的视图列表
 * 里也看得到它们。
 *
 * 几处不照 4.1 字面、照引擎能做到的做，理由都写在用到的地方，也同步进了
 * scenarios.md 的「第 4 批的决定与偏差」：
 * - 日报的「日期」默认是**近 30 天**，指标卡读其中最后一个过完的日（09-21，
 *   即昨日）并与前一日比：板上的日期筛选只能收窄每个面板，「较前一日」与
 *   「30 天走势」要的是以那一天为终点的一段窗口，筛成「昨日」就只剩一个点。
 * - 「退款率」按下单日算会被还没发生的退款拉低，所以日报的那张卡是按退款日
 *   算的「售后退款」金额；成熟口径的退款率在销售复盘与履约与售后里。
 * - 「固定范围」是批 C 之前存下的板子迁移出来的，新板子不用；履约与售后的
 *   「已付款」写在各面板自己的条件里。
 * ------------------------------------------------------------------------ */

import type {
  AnalysisGroup,
  AnalysisMetric,
  AnalysisViewConfig,
  DashboardField,
  DashboardPanel,
  DashboardViewConfig,
  FilterNode,
  PanelBinding,
  PanelClick,
  PanelLayout,
  RecordViewConfig,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import {
  CHANNEL_OPTIONS,
  LEVEL_OPTIONS,
  RETAIL_AFTER_SALES,
  RETAIL_BOARDS,
  RETAIL_MEMBERS,
  RETAIL_MEMBERS_REMOTE,
  RETAIL_ORDERS,
  RETAIL_ORDER_EVENTS,
  RETAIL_WAYBILLS,
  SHOP_OPTIONS,
  WAREHOUSE_OPTIONS,
} from './boardDefinitions.js';

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
const BREACHED: FilterNode = {
  field: 'state.shipSlaBreached',
  operator: 'EQ',
  value: true,
};
const ON_TIME: FilterNode = {
  field: 'state.shipSlaBreached',
  operator: 'EQ',
  value: false,
};

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

/** 一个指标除以另一个；`percent` 时乘 100，读作「（%）」的表格列。 */
const ratio = (
  alias: string,
  label: string,
  over: string,
  under: string,
  percent = false,
): AnalysisMetric => ({
  type: 'DERIVED',
  alias,
  label,
  expression: {
    type: 'BINARY',
    operator: 'DIVIDE',
    left: percent
      ? {
          type: 'BINARY',
          operator: 'MULTIPLY',
          left: { type: 'METRIC_REF', metric: over },
          right: { type: 'CONSTANT', value: 100 },
        }
      : { type: 'METRIC_REF', metric: over },
    right: { type: 'METRIC_REF', metric: under },
  },
});

const byDay = (field: string, alias = 'day'): AnalysisGroup => ({
  type: 'DATE_HISTOGRAM',
  field,
  alias,
  unit: 'DAY',
  dense: true,
});

const byMonth = (field: string, alias = 'month'): AnalysisGroup => ({
  type: 'DATE_HISTOGRAM',
  field,
  alias,
  unit: 'MONTH',
});

const terms = (
  field: string,
  alias: string,
  label?: string,
  missingKey?: string,
): AnalysisGroup => ({
  type: 'TERMS',
  field,
  alias,
  ...(label ? { label } : {}),
  ...(missingKey ? { missingKey } : {}),
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

function record(
  config: Pick<RecordViewConfig, 'table' | 'card'> & Partial<RecordViewConfig>,
): RecordViewConfig {
  return {
    kind: 'record',
    filter: and(),
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    pageSize: 10,
    layout: 'table',
    summaries: [],
    ...config,
  };
}

const columns = (...fields: string[]) => ({
  columns: fields.map(field => ({ field })),
});

function shared(
  id: string,
  definitionId: string,
  title: string,
  config: ViewInstance['config'],
): ViewInstance {
  return { id, definitionId, title, scope: 'shared', revision: '1', config };
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

/** 板子自己的一个分析面板（D22 C）。 */
function owned(
  id: string,
  title: string,
  definitionId: string,
  config: AnalysisViewConfig,
  layout: PanelLayout,
  bindings: PanelBinding[],
  extra: { tab?: string; click?: PanelClick } = {},
): DashboardPanel {
  return {
    id,
    kind: 'view',
    title,
    owned: { definitionId, config },
    bindings,
    layout,
    ...extra,
  };
}

/** 一个引用已存视图的面板。 */
function saved(
  id: string,
  title: string,
  instanceId: string,
  layout: PanelLayout,
  bindings: PanelBinding[],
  extra: { tab?: string; click?: PanelClick } = {},
): DashboardPanel {
  return { id, kind: 'view', title, instanceId, bindings, layout, ...extra };
}

// ---------------------------------------------------------------- 共享的已存视图

/** 付款超过 48 小时仍未发货的单，最早付款的在最前：日报与履约板的明细。 */
export const OVERDUE_VIEW = 'retail-overdue';
/** 订单明细：点一个商品打开的那一张，带着商品与时间作条件。 */
export const ORDER_LIST_VIEW = 'retail-order-list';
/** 售后单明细：仅退款与退货退款，最新申请的在最前。 */
export const AFTER_SALE_VIEW = 'retail-after-sale-list';
/** 一位会员的订单（会员详情页的板上）。 */
export const MEMBER_ORDERS_VIEW = 'retail-member-orders';
/** 一位会员的售后。 */
export const MEMBER_AFTER_SALES_VIEW = 'retail-member-after-sales';
/** 一张单的事件流：模板，按订单号填写，按版本排序（订单详情页）。 */
export const ORDER_HISTORY_VIEW = 'retail-order-history';
/** 一张单的商品行：展开 `items`，一行一个商品（订单详情页）。 */
export const ORDER_LINES_VIEW = 'retail-order-lines';

const ORDER_CARD = {
  title: 'state.orderNo',
  fields: ['state.buyer.nick', 'state.status', 'state.amounts.paidAmount'],
};

export const retailViews: ViewInstance[] = [
  shared(
    OVERDUE_VIEW,
    RETAIL_ORDERS,
    '付款超过 48 小时仍未发货',
    record({
      filter: and(
        { field: 'state.status', operator: 'IN', value: ['PAID'] },
        BREACHED,
      ),
      sort: [{ field: 'state.timing.paidAt', direction: 'ASC' }],
      // A board's record panel shows one page and no pager (see scenarios.md,
      // engine gaps): a page of 20 holds a normal day's overdue queue whole.
      pageSize: 20,
      table: columns(
        'state.orderNo',
        'state.buyer.nick',
        'state.warehouse',
        'state.channel',
        'state.timing.paidAt',
        'state.timing.shipDueAt',
        'state.amounts.paidAmount',
        'state.items',
      ),
      card: ORDER_CARD,
    }),
  ),
  shared(
    ORDER_LIST_VIEW,
    RETAIL_ORDERS,
    '订单明细',
    record({
      sort: [{ field: 'firstEventTime', direction: 'DESC' }],
      pageSize: 20,
      summaries: [{ field: 'state.amounts.paidAmount', fn: 'SUM' }],
      table: columns(
        'state.orderNo',
        'firstEventTime',
        'state.buyer.nick',
        'state.status',
        'state.channel',
        'state.items',
        'state.amounts.paidAmount',
        'state.amounts.refundedAmount',
      ),
      card: ORDER_CARD,
    }),
  ),
  shared(
    AFTER_SALE_VIEW,
    RETAIL_AFTER_SALES,
    '售后单明细',
    record({
      filter: and({
        field: 'state.type',
        operator: 'IN',
        value: ['REFUND_ONLY', 'RETURN_REFUND'],
      }),
      sort: [{ field: 'state.requestedAt', direction: 'DESC' }],
      summaries: [{ field: 'state.refundedAmount', fn: 'SUM' }],
      table: columns(
        'state.afterSaleNo',
        'state.orderNo',
        'state.title',
        'state.type',
        'state.reason',
        'state.status',
        'state.refundedAmount',
        'state.requestedAt',
      ),
      card: {
        title: 'state.afterSaleNo',
        fields: ['state.title', 'state.reason', 'state.refundedAmount'],
      },
    }),
  ),
  shared(
    MEMBER_ORDERS_VIEW,
    RETAIL_ORDERS,
    '会员的订单',
    record({
      sort: [{ field: 'firstEventTime', direction: 'DESC' }],
      summaries: [{ field: 'state.amounts.paidAmount', fn: 'SUM' }],
      table: columns(
        'state.orderNo',
        'firstEventTime',
        'state.status',
        'state.items',
        'state.amounts.paidAmount',
        'state.amounts.refundedAmount',
      ),
      card: ORDER_CARD,
    }),
  ),
  shared(
    MEMBER_AFTER_SALES_VIEW,
    RETAIL_AFTER_SALES,
    '会员的售后',
    record({
      sort: [{ field: 'state.requestedAt', direction: 'DESC' }],
      pageSize: 5,
      table: columns(
        'state.orderNo',
        'state.title',
        'state.reason',
        'state.status',
        'state.refundedAmount',
        'state.requestedAt',
      ),
      card: {
        title: 'state.afterSaleNo',
        fields: ['state.title', 'state.reason', 'state.refundedAmount'],
      },
    }),
  ),
  shared(
    ORDER_HISTORY_VIEW,
    RETAIL_ORDER_EVENTS,
    '订单历史',
    record({
      // 模板：填一张单，按版本读它的历史。
      filter: and({ field: 'aggregateId', operator: 'EQ', value: '' }),
      sort: [{ field: 'version', direction: 'ASC' }],
      pageSize: 20,
      table: columns('version', 'createTime', 'body'),
      card: { title: 'version', fields: ['createTime', 'body'] },
    }),
  ),
  shared(
    ORDER_LINES_VIEW,
    RETAIL_ORDERS,
    '订单商品行',
    analysis({
      elements: [{ path: 'state.items' }],
      groups: [terms('state.items.title', 'title', '商品')],
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
  ),
];

// ---------------------------------------------------------------- 运营日报

export const OPS_DAILY = 'retail-ops-daily';

/** 日报默认看的那段时间：近 30 天，指标卡读其中最后一个过完的日。 */
export const DAILY_WINDOW = {
  type: 'relative',
  amount: 30,
  unit: 'day',
} as const;

const DAILY_FIELDS: DashboardField[] = [
  {
    name: 'date',
    label: '日期',
    kind: 'datetime',
    default: DAILY_WINDOW,
    required: true,
  },
  {
    name: 'channel',
    label: '渠道',
    kind: 'enum',
    options: CHANNEL_OPTIONS,
    multiple: true,
  },
  {
    name: 'shop',
    label: '店铺',
    kind: 'enum',
    options: SHOP_OPTIONS,
    multiple: true,
  },
  // 搜索一类（D36）：只接明细面板，编译成 Wow 的 SEARCH。搭建界面的「添加筛选」
  // 还不列这一类（另有 todo），所以它在配置里声明。
  { name: 'q', label: '搜索订单', kind: 'search' },
];

/** 订单面板接日报的三枚筛选；`date` 接到 `dateField`。 */
const orderBindings = (dateField = 'firstEventTime') => [
  bind('date', dateField),
  bind('channel', 'state.channel'),
  bind('shop', 'state.shopId'),
];

const afterSaleBindings = (dateField: string) => [
  bind('date', dateField),
  bind('channel', 'state.channel'),
  bind('shop', 'state.shopId'),
];

/**
 * 一张指标卡：按日分桶的走势，读作最后一个过完的日与前一日之比（`last`）。
 * 引擎的默认读法正是运营要的：09-22 上午打开时，今天还没过完，卡片读 09-21，
 * 并注明「9 月 22 日还没过完」。
 */
function card(
  metrics: [AnalysisMetric, ...AnalysisMetric[]],
  metric: string,
  options: {
    timeField?: string;
    format?: 'percent';
    lowerIsBetter?: boolean;
    target?: number;
  } = {},
): AnalysisViewConfig {
  return analysis({
    groups: [byDay(options.timeField ?? 'firstEventTime')],
    metrics,
    sort: [{ alias: 'day', direction: 'ASC' }],
    limit: 62,
    chart: {
      type: 'metric',
      metric: {
        metric,
        trend: {
          x: 'day',
          ...(options.lowerIsBetter ? { lowerIsBetter: true } : {}),
        },
        ...(options.format ? { format: options.format } : {}),
        ...(options.target === undefined ? {} : { target: options.target }),
      },
    },
  });
}

/**
 * 一张没有走势的卡：读「昨日」这一天（指标自带条件），与这段时间的整体水平
 * 比（`compare`，百分比变化）。比值与平均不能相加，引擎不给它们画走势，也就
 * 没有「较前一日」——「前天」不是一个命名时段，相对窗口又不按日对齐（见
 * scenarios.md 的引擎缺口）。
 */
function levelCard(
  metrics: [AnalysisMetric, ...AnalysisMetric[]],
  metric: string,
  against: string,
  options: { format?: 'percent'; target?: number } = {},
): AnalysisViewConfig {
  return analysis({
    groups: [],
    metrics,
    limit: 1,
    chart: {
      type: 'metric',
      metric: {
        metric,
        compare: { metric: against, mode: 'percent' },
        ...(options.format ? { format: options.format } : {}),
        ...(options.target === undefined ? {} : { target: options.target }),
      },
    },
  });
}

const YESTERDAY = (field: string): FilterNode => ({
  field,
  operator: 'BETWEEN',
  value: { type: 'preset', preset: 'yesterday' },
});

/** 日报上八张卡的标题，左上到右下。 */
export const DAILY_CARDS = [
  'GMV',
  '实付金额',
  '订单数（单）',
  '新客数（人）',
  '客单价 · 昨日较近 30 天',
  '支付转化率 · 昨日较近 30 天',
  '售后退款',
  '发货及时率 · 昨日较近 30 天',
] as const;

function opsDailyConfig(): DashboardViewConfig {
  // 第一眼（6.3）：五张带走势的卡一行，三张比值与平均的卡一行（它们没有走势，
  // 矮一截），逐时 GMV 紧跟在下面，1280×800 不滚动就看得到它的开头。
  const cardAt = (index: number) =>
    [
      at(0, 0, 5, 3),
      at(5, 0, 5, 3),
      at(10, 0, 5, 3),
      at(15, 0, 5, 3),
      at(0, 3, 8, 2),
      at(8, 3, 8, 2),
      at(20, 0, 4, 3),
      at(16, 3, 8, 2),
    ][index];
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
        RETAIL_ORDERS,
        card([sum('gmv', 'state.amounts.payableAmount', 'GMV')], 'gmv'),
        cardAt(0),
        orderBindings(),
      ),
      owned(
        'paid',
        paid,
        RETAIL_ORDERS,
        card([sum('paid', 'state.amounts.paidAmount', '实付金额')], 'paid'),
        cardAt(1),
        orderBindings(),
      ),
      owned(
        'orders',
        orders,
        RETAIL_ORDERS,
        card([count('orders', '订单数')], 'orders'),
        cardAt(2),
        orderBindings(),
      ),
      // 新客数：买家第一张付款子单上 `isNewBuyer` 为真，所以按天数它就是那天
      // 来的新客，能相加，才画得出走势。「买家数」是去重计数，不能相加，引擎
      // 不给它画走势（见 scenarios.md 的引擎缺口），它在销售复盘的月度表里。
      owned(
        'new-buyers',
        buyers,
        RETAIL_ORDERS,
        card(
          [
            count('newBuyers', '新客数', [
              { field: 'state.buyer.isNewBuyer', operator: 'EQ', value: true },
            ]),
          ],
          'newBuyers',
        ),
        cardAt(3),
        orderBindings(),
      ),
      // 下面三张是比值或平均，引擎只给能相加的指标画走势，所以这三张读「昨日」
      // 这一天，与这段时间（近 30 天）的整体水平比。客单价 = GMV ÷ 订单数，就是
      // 应付金额的平均：写成平均，它才带着金额的单位。
      owned(
        'aov',
        aov,
        RETAIL_ORDERS,
        levelCard(
          [
            avg('aov', 'state.amounts.payableAmount', '昨日客单价', [
              YESTERDAY('firstEventTime'),
            ]),
            avg('aovWindow', 'state.amounts.payableAmount', '近 30 天客单价'),
          ],
          'aov',
          'aovWindow',
        ),
        cardAt(4),
        orderBindings(),
      ),
      owned(
        'conversion',
        conversion,
        RETAIL_ORDERS,
        levelCard(
          [
            count('placed', '昨日下单', [YESTERDAY('firstEventTime')]),
            count('paidOrders', '昨日付款', [
              YESTERDAY('firstEventTime'),
              PAID,
            ]),
            ratio('conversion', '昨日支付转化率', 'paidOrders', 'placed'),
            count('placedWindow', '下单'),
            count('paidWindow', '付款', [PAID]),
            ratio(
              'conversionWindow',
              '近 30 天支付转化率',
              'paidWindow',
              'placedWindow',
            ),
          ],
          'conversion',
          'conversionWindow',
          { format: 'percent' },
        ),
        cardAt(5),
        orderBindings(),
      ),
      owned(
        'refund',
        refund,
        RETAIL_AFTER_SALES,
        card(
          [sum('refunded', 'state.refundedAmount', '售后退款')],
          'refunded',
          { timeField: 'state.refundedAt', lowerIsBetter: true },
        ),
        cardAt(6),
        afterSaleBindings('state.refundedAt'),
      ),
      // 发货及时率按发货期限算：期限（付款 + 48 小时）落在那一天、未取消的单里，
      // 按时发出的比例（scenarios.md 2.6 A7）；目标 95%。
      owned(
        'on-time',
        onTime,
        RETAIL_ORDERS,
        levelCard(
          [
            count('due', '昨日到期', [
              YESTERDAY('state.timing.shipDueAt'),
              NOT_CANCELLED,
            ]),
            count('onTime', '昨日按时发出', [
              YESTERDAY('state.timing.shipDueAt'),
              NOT_CANCELLED,
              ON_TIME,
            ]),
            ratio('rate', '昨日发货及时率', 'onTime', 'due'),
            count('dueWindow', '到期', [NOT_CANCELLED]),
            count('onTimeWindow', '按时发出', [NOT_CANCELLED, ON_TIME]),
            ratio(
              'rateWindow',
              '近 30 天发货及时率',
              'onTimeWindow',
              'dueWindow',
            ),
          ],
          'rate',
          'rateWindow',
          { format: 'percent', target: 0.95 },
        ),
        cardAt(7),
        orderBindings('state.timing.shipDueAt'),
      ),
      owned(
        'hourly',
        '今日与昨日的逐时 GMV',
        RETAIL_ORDERS,
        analysis({
          groups: [terms('state.placedHour', 'hour', '下单时段（点）')],
          metrics: [
            sum('yesterday', 'state.amounts.payableAmount', '昨日', [
              {
                field: 'firstEventTime',
                operator: 'BETWEEN',
                value: { type: 'preset', preset: 'yesterday' },
              },
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
        at(0, 5, 14, 4),
        orderBindings(),
      ),
      owned(
        'by-channel',
        '渠道分布',
        RETAIL_ORDERS,
        analysis({
          groups: [terms('state.channel', 'channel', '渠道')],
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
        at(14, 5, 10, 4),
        orderBindings(),
        // 点一个渠道：整块板筛到这个渠道（交叉筛选，D22 I）。
        { click: { kind: 'filter', filter: 'channel' } },
      ),
      saved(
        'overdue',
        '付款超过 48 小时仍未发货',
        OVERDUE_VIEW,
        at(0, 9, 14, 5),
        [...orderBindings(), bind('q', 'q')],
      ),
      owned(
        'refund-skus',
        '售后退款最多的 5 个商品',
        RETAIL_AFTER_SALES,
        analysis({
          groups: [terms('state.title', 'sku', '商品')],
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
        at(14, 9, 10, 5),
        afterSaleBindings('state.refundedAt'),
        // 点一个商品：打开销售复盘的「品类」页，带上这块板的日期（D23 Q17）；
        // 退款率在那边的「退款率最高的 10 个商品」里读。按退款率排的那张要展开
        // 商品行，而展开了的分析没有可点的组（见 scenarios.md 的引擎缺口），
        // 所以日报这里按售后单的商品排。
        {
          click: {
            kind: 'dashboard',
            instanceId: SALES_REVIEW,
            values: { date: { filter: 'date' } },
          },
        },
      ),
      {
        id: 'runbook',
        kind: 'links',
        title: '值班手册',
        layout: at(0, 14, 24, 2),
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

/**
 * 近 3 个月：浴巾 5 月起才退得多（A1），全部 25 个月拉平了它，排不进前 10。
 */
const LAST_3_MONTHS: FilterNode = {
  field: 'firstEventTime',
  operator: 'GTE',
  value: { type: 'relative', amount: 3, unit: 'month' },
};

/**
 * 退款率最高的商品：展开商品行，按商品分组；只保留件数够多的（少了一两件的
 * 退款率没有意义），按退款率倒序。退款率是比值，图的轴读作百分比。
 */
function topRefundSkus(
  limit: number,
  minQty: number,
  ...filter: FilterNode[]
): AnalysisViewConfig {
  return analysis({
    filter: and(...filter),
    elements: [{ path: 'state.items' }],
    groups: [terms('state.items.title', 'sku', '商品')],
    metrics: [
      sum('qty', 'state.items.qty', '件数'),
      sum('pay', 'state.items.payAmount', '实付'),
      sum('refunded', 'state.items.refundedAmount', '已退'),
      ratio('rate', '退款率（%）', 'refunded', 'pay', true),
    ],
    having: {
      type: 'CONDITION',
      metric: 'qty',
      operator: 'GTE',
      value: minQty,
    },
    sort: [{ alias: 'rate', direction: 'DESC' }],
    limit,
    chart: {
      type: 'bar',
      cartesian: {
        x: 'sku',
        series: [{ metric: 'rate' }],
        orientation: 'horizontal',
      },
      legend: 'none',
    },
  });
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
  // 可选、没有默认值：复盘默认看全部 25 个月，指标卡自己读最近一个过完的月；
  // 设了日期，整块板都收窄到那一段（日报的点击就这样把日期带过来）。
  { name: 'date', label: '日期', kind: 'datetime' },
  {
    name: 'channel',
    label: '渠道',
    kind: 'enum',
    options: CHANNEL_OPTIONS,
    multiple: true,
  },
  {
    name: 'level',
    label: '会员等级',
    kind: 'enum',
    options: LEVEL_OPTIONS,
    multiple: true,
  },
];

const salesBindings = () => [
  bind('date', 'firstEventTime'),
  bind('channel', 'state.channel'),
  bind('level', 'state.buyer.level'),
];

/**
 * 月度复盘的一张卡：按月的走势，读作最近一个过完的月（8 月）较上一个月——
 * 9 月还没过完，拿 22 天比 31 天每个月初都会读成下跌。整板改成按日时，它读作
 * 昨日较前一日，与日报同一个口径。
 */
function monthCard(
  metrics: [AnalysisMetric, ...AnalysisMetric[]],
  metric: string,
  format?: 'percent',
): AnalysisViewConfig {
  return analysis({
    filter: and({
      field: 'firstEventTime',
      operator: 'GTE',
      value: { type: 'preset', preset: 'lastYear' },
    }),
    groups: [byMonth('firstEventTime')],
    metrics,
    sort: [{ alias: 'month', direction: 'ASC' }],
    limit: 400,
    chart: {
      type: 'metric',
      metric: {
        metric,
        trend: { x: 'month' },
        ...(format ? { format } : {}),
      },
    },
  });
}

/** 销售复盘概览的三张卡。 */
export const SALES_CARDS = ['GMV', '实付金额', '订单数（单）'] as const;

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

function salesReviewConfig(): DashboardViewConfig {
  const overview = { tab: 'overview' };
  const category = { tab: 'category' };
  const channel = { tab: 'channel' };
  const customer = { tab: 'customer' };
  const [gmv, paid, orders] = SALES_CARDS;
  const cardAt = (index: number) => at(index * 8, 0, 8, 3);
  // 能相加的三个指标画成带走势的卡；主单数、买家数（去重计数）与客单价（平均）
  // 不能相加，引擎不给它们画走势，它们在下面的月度表里逐月读。
  const cards: [string, string, AnalysisViewConfig][] = [
    [
      'gmv',
      gmv,
      monthCard([sum('gmv', 'state.amounts.payableAmount', 'GMV')], 'gmv'),
    ],
    [
      'paid',
      paid,
      monthCard([sum('paid', 'state.amounts.paidAmount', '实付金额')], 'paid'),
    ],
    ['orders', orders, monthCard([count('orders', '订单数')], 'orders')],
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
          RETAIL_ORDERS,
          config,
          cardAt(index),
          salesBindings(),
          overview,
        ),
      ),
      // A-02：25 个月的 GMV（柱）与客单价（线，右轴）。
      owned(
        'trend',
        'GMV 与客单价（按月）',
        RETAIL_ORDERS,
        analysis({
          groups: [byMonth('firstEventTime')],
          metrics: [
            sum('gmv', 'state.amounts.payableAmount', 'GMV'),
            avg('aov', 'state.amounts.payableAmount', '客单价'),
          ],
          sort: [{ alias: 'month', direction: 'ASC' }],
          limit: 400,
          chart: {
            type: 'combo',
            cartesian: {
              x: 'month',
              series: [
                { metric: 'gmv', type: 'bar', axis: 'left' },
                { metric: 'aov', type: 'line', axis: 'right' },
              ],
            },
            legend: 'top',
          },
        }),
        at(0, 3, 14, 5),
        salesBindings(),
        overview,
      ),
      // A-03：8 月较 7 月，按渠道并排。复盘的是过完的两个整月，所以两个 GMV
      // 各带一个绝对的月份（9 月还没过完）。瀑布图只收能相加的指标，「8 月 −
      // 7 月」是派生指标，画不成瀑布（见 scenarios.md 的引擎缺口）。
      owned(
        'month-over-month',
        '7 月与 8 月 GMV：按渠道',
        RETAIL_ORDERS,
        analysis({
          groups: [terms('state.channel', 'channel', '渠道')],
          metrics: [
            sum('july', 'state.amounts.payableAmount', '7 月 GMV', [
              {
                field: 'firstEventTime',
                operator: 'BETWEEN',
                value: {
                  type: 'absolute',
                  from: '2026-07-01',
                  to: '2026-07-31',
                },
              },
            ]),
            sum('august', 'state.amounts.payableAmount', '8 月 GMV', [
              {
                field: 'firstEventTime',
                operator: 'BETWEEN',
                value: {
                  type: 'absolute',
                  from: '2026-08-01',
                  to: '2026-08-31',
                },
              },
            ]),
          ],
          sort: [{ alias: 'august', direction: 'DESC' }],
          limit: 10,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'channel',
              series: [{ metric: 'july' }, { metric: 'august' }],
            },
            legend: 'top',
          },
        }),
        at(14, 3, 10, 5),
        salesBindings(),
        overview,
      ),
      // 月度指标：今年逐月的订单、主单、买家与客单价——去重计数与平均在这里读。
      owned(
        'monthly',
        '月度指标（今年）',
        RETAIL_ORDERS,
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
      // A-04：一级类目 → 二级类目的实付。
      owned(
        'treemap',
        '实付的品类构成',
        RETAIL_ORDERS,
        analysis({
          elements: [{ path: 'state.items' }],
          groups: [
            terms('state.items.category1', 'category1', '一级类目'),
            terms('state.items.category2', 'category2', '二级类目'),
          ],
          metrics: [sum('pay', 'state.items.payAmount', '实付')],
          sort: [{ alias: 'pay', direction: 'DESC' }],
          limit: 100,
          chart: {
            type: 'treemap',
            treemap: {
              parent: 'category1',
              category: 'category2',
              value: 'pay',
            },
          },
        }),
        at(0, 0, 12, 5),
        salesBindings(),
        category,
      ),
      // A-16：成交单价按 ¥100 分段，件数与实付。
      owned(
        'price-bands',
        '价格带：件数与实付',
        RETAIL_ORDERS,
        analysis({
          elements: [{ path: 'state.items' }],
          groups: [
            {
              type: 'HISTOGRAM',
              field: 'state.items.salePrice',
              alias: 'price',
              interval: 100,
              label: '成交单价（每 ¥100 一段）',
            },
          ],
          metrics: [
            sum('qty', 'state.items.qty', '件数'),
            sum('pay', 'state.items.payAmount', '实付'),
          ],
          sort: [{ alias: 'price', direction: 'ASC' }],
          limit: 30,
          chart: {
            type: 'combo',
            cartesian: {
              x: 'price',
              series: [
                { metric: 'qty', type: 'bar', axis: 'left' },
                { metric: 'pay', type: 'line', axis: 'right' },
              ],
            },
            legend: 'top',
          },
        }),
        at(12, 0, 12, 5),
        salesBindings(),
        category,
      ),
      // A-07：件数 ≥ 30 的商品里退款率最高的 10 个；点一个商品，打开订单明细，
      // 带上这个商品（与板上的日期、渠道）。
      owned(
        'refund-skus',
        '退款率最高的 10 个商品（近 3 个月）',
        RETAIL_ORDERS,
        { ...topRefundSkus(10, 30, LAST_3_MONTHS), layout: 'table' },
        at(0, 5, 24, 5),
        salesBindings(),
        category,
      ),
      // A-05：渠道结构按月的份额；点一个渠道，整块板筛到它。
      owned(
        'channel-mix',
        '渠道结构（按月份额）',
        RETAIL_ORDERS,
        analysis({
          groups: [
            byMonth('firstEventTime'),
            terms('state.channel', 'channel', '渠道'),
          ],
          metrics: [sum('gmv', 'state.amounts.payableAmount', 'GMV')],
          sort: [{ alias: 'month', direction: 'ASC' }],
          limit: 1000,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'month',
              splitBy: 'channel',
              series: [{ metric: 'gmv', stack: 'channel' }],
              percentStack: true,
            },
            legend: 'top',
          },
        }),
        at(0, 0, 14, 5),
        salesBindings(),
        { ...channel, click: { kind: 'filter', filter: 'channel' } },
      ),
      // A-06：省份前 15。
      owned(
        'provinces',
        '省份前 15（GMV）',
        RETAIL_ORDERS,
        analysis({
          groups: [terms('state.address.province', 'province', '省份')],
          metrics: [sum('gmv', 'state.amounts.payableAmount', 'GMV')],
          sort: [{ alias: 'gmv', direction: 'DESC' }],
          limit: 15,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'province',
              series: [{ metric: 'gmv' }],
              orientation: 'horizontal',
            },
            legend: 'none',
          },
        }),
        at(14, 0, 10, 5),
        salesBindings(),
        // 点一个省份：打开订单明细，条件就是这个省份（与板上的日期、渠道）。
        { ...channel, click: { kind: 'view', instanceId: ORDER_LIST_VIEW } },
      ),
      // A-06 另一张：城市等级 × 渠道，空值单成一组——旧版小程序没报城市（A5）。
      owned(
        'tiers',
        '城市等级 × 渠道（订单数）',
        RETAIL_ORDERS,
        analysis({
          groups: [
            terms('state.address.cityTier', 'tier', '城市等级', '（未上报）'),
            terms('state.channel', 'channel', '渠道'),
          ],
          metrics: [count('orders', '订单数')],
          sort: [],
          limit: 100,
          chart: {
            type: 'heatmap',
            heatmap: { x: 'channel', y: 'tier', value: 'orders' },
          },
        }),
        at(0, 5, 12, 5),
        salesBindings(),
        channel,
      ),
      // A-11：每场活动的 GMV 与客单价；平日单成一组。
      owned(
        'activities',
        '大促拉动：各活动的 GMV 与客单价',
        RETAIL_ORDERS,
        analysis({
          groups: [
            terms('state.promotion.activityId', 'activity', '活动', '平日'),
          ],
          metrics: [
            sum('gmv', 'state.amounts.payableAmount', 'GMV'),
            avg('aov', 'state.amounts.payableAmount', '客单价'),
          ],
          sort: [{ alias: 'gmv', direction: 'DESC' }],
          limit: 12,
          chart: {
            type: 'combo',
            cartesian: {
              x: 'activity',
              series: [
                { metric: 'gmv', type: 'bar', axis: 'left' },
                { metric: 'aov', type: 'line', axis: 'right' },
              ],
            },
            legend: 'top',
          },
        }),
        at(12, 5, 12, 5),
        salesBindings(),
        channel,
      ),
      // A-08：GMV 前 20 的买家，带合计行。
      owned(
        'top-buyers',
        'GMV 前 20 的买家',
        RETAIL_ORDERS,
        analysis({
          groups: [terms('state.buyer.id', 'buyer', '会员号')],
          metrics: [
            {
              type: 'ANY',
              alias: 'nick',
              label: '昵称',
              field: 'state.buyer.nick',
            },
            sum('gmv', 'state.amounts.payableAmount', 'GMV'),
            count('orders', '订单数'),
          ],
          sort: [{ alias: 'gmv', direction: 'DESC' }],
          limit: 20,
          layout: 'table',
          table: { columns: [], totals: true },
          chart: {
            type: 'bar',
            cartesian: { x: 'buyer', series: [{ metric: 'gmv' }] },
          },
        }),
        at(0, 0, 12, 6),
        salesBindings(),
        customer,
      ),
      // A-15：新客与老客各贡献多少，按月堆叠。
      owned(
        'new-vs-returning',
        '新客与老客的 GMV（按月）',
        RETAIL_ORDERS,
        analysis({
          groups: [
            byMonth('firstEventTime'),
            terms('state.buyer.isNewBuyer', 'isNew', '新客'),
          ],
          metrics: [sum('gmv', 'state.amounts.payableAmount', 'GMV')],
          sort: [{ alias: 'month', direction: 'ASC' }],
          limit: 1000,
          chart: {
            type: 'area',
            cartesian: {
              x: 'month',
              splitBy: 'isNew',
              series: [{ metric: 'gmv', stack: 'buyers' }],
            },
            legend: 'top',
          },
        }),
        at(12, 0, 12, 6),
        salesBindings(),
        customer,
      ),
      // A-18：按首单月看复购率（会员数据；会员没有下单时间与渠道，所以日期与
      // 渠道接不上它，面板头会说「不受…影响」）。
      owned(
        'repurchase',
        '复购率（按首单月，%）',
        RETAIL_MEMBERS,
        analysis({
          filter: and({
            field: 'state.firstOrderAt',
            operator: 'IS_NOT_NULL',
            value: null,
          }),
          groups: [byMonth('state.firstOrderAt')],
          metrics: [
            count('members', '会员数'),
            count('repeat', '复购会员数', [
              { field: 'state.orderCount', operator: 'GTE', value: 2 },
            ]),
            ratio('rate', '复购率（%）', 'repeat', 'members', true),
          ],
          sort: [{ alias: 'month', direction: 'ASC' }],
          limit: 400,
          chart: {
            type: 'bar',
            cartesian: { x: 'month', series: [{ metric: 'rate' }] },
            legend: 'none',
          },
        }),
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
  // 可选：不设就看全部 25 个月——春节停运（A6）与双 11 的积压都在里面。
  { name: 'date', label: '日期', kind: 'datetime' },
  {
    name: 'warehouse',
    label: '仓库',
    kind: 'enum',
    options: WAREHOUSE_OPTIONS,
    multiple: true,
  },
  { name: 'province', label: '省份', kind: 'string', multiple: true },
  {
    name: 'channel',
    label: '渠道',
    kind: 'enum',
    options: CHANNEL_OPTIONS,
    multiple: true,
  },
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
  // 这块板只看已付款的单：写在各订单面板自己的条件里（见文件头）。
  const paid = and(PAID, NOT_CANCELLED);
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
      // A-12：按周的发货超时率，红线 5%。补空桶：春节停运那几周也有点。
      owned(
        'breach-rate',
        '发货超时率（按付款周）',
        RETAIL_ORDERS,
        analysis({
          filter: paid,
          groups: [
            {
              type: 'DATE_HISTOGRAM',
              field: 'state.timing.paidAt',
              alias: 'week',
              unit: 'WEEK',
              dense: true,
            },
          ],
          metrics: [
            count('paidOrders', '已付款'),
            count('breached', '超时', [BREACHED]),
            ratio('rate', '超时率', 'breached', 'paidOrders'),
          ],
          sort: [{ alias: 'week', direction: 'ASC' }],
          limit: 200,
          chart: {
            type: 'line',
            cartesian: {
              x: 'week',
              series: [{ metric: 'rate' }],
              yAxis: { left: { format: 'percent' } },
              referenceLines: [{ axis: 'left', value: 0.05, label: '红线 5%' }],
            },
            legend: 'none',
          },
        }),
        at(0, 0, 24, 5),
        orderWires(),
        ship,
      ),
      // A-12 另一张：付款到发货的 P50 与 P90（≈），按仓库。
      owned(
        'ship-hours',
        '付款到发货的小时数：P50 与 P90（≈）',
        RETAIL_ORDERS,
        analysis({
          filter: and(PAID, {
            field: 'state.payToShipHours',
            operator: 'IS_NOT_NULL',
            value: null,
          }),
          groups: [terms('state.warehouse', 'warehouse', '仓库')],
          metrics: [
            {
              type: 'PERCENTILE',
              alias: 'p50',
              label: 'P50（小时）',
              expression: { type: 'FIELD', field: 'state.payToShipHours' },
              percentile: 50,
            },
            {
              type: 'PERCENTILE',
              alias: 'p90',
              label: 'P90（小时）',
              expression: { type: 'FIELD', field: 'state.payToShipHours' },
              percentile: 90,
            },
          ],
          sort: [{ alias: 'p90', direction: 'DESC' }],
          limit: 10,
          chart: {
            type: 'bar',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'p50' }, { metric: 'p90' }],
            },
            legend: 'top',
          },
        }),
        at(0, 5, 8, 4),
        orderWires(),
        ship,
      ),
      // A-13：承运商 × 周的签收时长（今年）。设「省份 = 广东省」，中通 7 月下旬
      // 那几格一下亮起来（A2 台风）。
      owned(
        'carrier-weeks',
        '承运商 × 周：平均签收时长（小时，今年）',
        RETAIL_WAYBILLS,
        analysis({
          filter: and({
            field: 'state.shippedAt',
            operator: 'GTE',
            value: { type: 'preset', preset: 'thisYear' },
          }),
          groups: [
            terms('state.carrier', 'carrier', '承运商'),
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
        ship,
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
      owned(
        'reasons',
        '售后理由',
        RETAIL_AFTER_SALES,
        analysis({
          groups: [terms('state.reason', 'reason', '售后理由')],
          metrics: [count('cases', '售后单数')],
          sort: [{ alias: 'cases', direction: 'DESC' }],
          limit: 10,
          chart: {
            type: 'pie',
            pie: { category: 'reason', value: 'cases', donut: true },
            legend: 'right',
          },
        }),
        at(0, 0, 10, 5),
        afterWires(),
        { ...after, click: { kind: 'view', instanceId: AFTER_SALE_VIEW } },
      ),
      owned(
        'refund-skus',
        '退款率最高的 10 个商品（近 3 个月）',
        RETAIL_ORDERS,
        { ...topRefundSkus(10, 30, LAST_3_MONTHS, PAID), layout: 'table' },
        at(10, 0, 14, 5),
        orderWires(),
        after,
      ),
      owned(
        'daily-refunds',
        '每日退款金额（上月至今）',
        RETAIL_AFTER_SALES,
        analysis({
          filter: and({
            field: 'state.refundedAt',
            operator: 'GTE',
            value: { type: 'preset', preset: 'lastMonth' },
          }),
          groups: [byDay('state.refundedAt')],
          metrics: [sum('refunded', 'state.refundedAmount', '退款金额')],
          sort: [{ alias: 'day', direction: 'ASC' }],
          limit: 100,
          chart: {
            type: 'bar',
            cartesian: { x: 'day', series: [{ metric: 'refunded' }] },
            legend: 'none',
            labels: false,
          },
        }),
        at(0, 5, 24, 4),
        afterWires(),
        after,
      ),
      saved(
        'after-sales',
        '售后单明细（仅退款、退货退款）',
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
        remote: RETAIL_MEMBERS_REMOTE,
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
        RETAIL_ORDERS,
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
        RETAIL_ORDERS,
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
        RETAIL_ORDERS,
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
      saved('orders', '他的订单', MEMBER_ORDERS_VIEW, at(0, 4, 24, 5), orders),
      saved(
        'after-sales',
        '他的售后',
        MEMBER_AFTER_SALES_VIEW,
        at(0, 9, 24, 4),
        [bind('buyer', 'state.buyerId'), bind('placed', 'state.requestedAt')],
      ),
    ],
  };
}

// ---------------------------------------------------------------- 全部

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

/** 一台零售引擎的存储里起步就有的：共享视图与四块板。 */
export const retailInstances: ViewInstance[] = [
  ...retailViews,
  ...retailBoards,
];
