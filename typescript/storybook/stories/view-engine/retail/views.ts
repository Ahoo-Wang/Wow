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
 * 业务场景的视图（docs/scenarios.md 第 3、4 节）：客服与运营的系统视图写在
 * 定义里，分析师回答 A-01～A-18 的分析是运营组共享的已存视图，另有几张个人
 * 视图。每张视图的注释写明它回答哪个问题、埋下的哪处异常在它里面看得见。
 *
 * 比率类的派生指标（退款率、超时率、复购率、优惠占比）乘了 100、名字里写
 * 「（%）」：派生指标不属于任何字段，引擎按两位小数的普通数字写它，还没有
 * 「百分比」这种读法（docs/scenarios.md 6.4 记下的缺口）。
 * ------------------------------------------------------------------------ */

import {
  DEFAULT_MISSING_KEY,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type FilterLeaf,
  type FilterNode,
  type FilterTree,
  type RecordViewConfig,
  type SystemView,
  type ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { BATH_TOWEL_SKU_ID, SKU_BY_ID } from './catalog.js';
import {
  WAYBILL_COLUMNS,
  afterSaleDefinition,
  eventType,
  memberDefinition,
  orderEventsDefinition,
  tradeOrderDefinition,
  waybillDefinition,
} from './definitions.js';

// ---------------------------------------------------------------- 定义的 id

export const RETAIL_ORDERS = 'retail-orders';
export const RETAIL_ORDER_ANALYSIS = 'retail-order-analysis';
export const RETAIL_AFTER_SALES = 'retail-after-sales';
export const RETAIL_MEMBERS = 'retail-members';
export const RETAIL_WAYBILLS = 'retail-waybills';
export const RETAIL_ORDER_EVENTS = 'retail-order-events';

/** A1 的那个浴巾：竹纤维浴巾 70×140 · 米白。 */
export const BATH_TOWEL_TITLE = SKU_BY_ID.get(BATH_TOWEL_SKU_ID)!.title;

// ---------------------------------------------------------------- 条件

function and(...children: FilterNode[]): FilterTree {
  return { op: 'and', children };
}

function leaf(
  field: string,
  operator: FilterLeaf['operator'],
  value: FilterLeaf['value'],
): FilterLeaf {
  return { field, operator, value };
}

function preset(field: string, name: string): FilterLeaf {
  return leaf(field, 'BETWEEN', { type: 'preset', preset: name });
}

/** 从「现在」往前数的一段：`近 3 个月`。 */
function recent(
  field: string,
  amount: number,
  unit: 'day' | 'month',
): FilterLeaf {
  return leaf(field, 'BETWEEN', { type: 'relative', amount, unit });
}

/** 上海时间的一段日历日，两头都含：`2025-10-15`～`2025-11-20`。 */
function days(field: string, from: string, to: string): FilterLeaf {
  return leaf(field, 'BETWEEN', {
    type: 'absolute',
    from: `${from}T00:00:00+08:00`,
    to: `${to}T23:59:59.999+08:00`,
  });
}

// ---------------------------------------------------------------- 指标

const SUM = 'SUM';

function sum(alias: string, field: string, label: string, filter?: FilterTree) {
  return {
    alias,
    type: 'NUMERIC',
    function: SUM,
    expression: { type: 'FIELD', field },
    label,
    ...(filter ? { filter } : {}),
  } as const satisfies AnalysisMetric;
}

function count(alias: string, label: string, filter?: FilterTree) {
  return {
    alias,
    type: 'COUNT',
    label,
    ...(filter ? { filter } : {}),
  } as const satisfies AnalysisMetric;
}

function ratio(
  alias: string,
  label: string,
  over: string,
  under: string,
  scale = 1,
): AnalysisMetric {
  const quotient = {
    type: 'BINARY',
    operator: 'DIVIDE',
    left: { type: 'METRIC_REF', metric: over },
    right: { type: 'METRIC_REF', metric: under },
  } as const;
  return {
    alias,
    type: 'DERIVED',
    label,
    expression:
      scale === 1
        ? quotient
        : {
            type: 'BINARY',
            operator: 'MULTIPLY',
            left: quotient,
            right: { type: 'CONSTANT', value: scale },
          },
  };
}

const GMV = 'state.amounts.payableAmount';
const PAID = 'state.amounts.paidAmount';
const REFUNDED = 'state.amounts.refundedAmount';

const gmv = (filter?: FilterTree) => sum('gmv', GMV, 'GMV', filter);
const orders = (filter?: FilterTree) => count('orders', '订单数', filter);
const aov = ratio('aov', '客单价', 'gmv', 'orders');

// ---------------------------------------------------------------- 维度

function byTerms(field: string, alias: string, label?: string): AnalysisGroup {
  return { type: 'TERMS', field, alias, ...(label ? { label } : {}) };
}

function byDate(
  field: string,
  alias: string,
  unit: 'DAY' | 'WEEK' | 'MONTH',
  label: string,
  dense = false,
): AnalysisGroup {
  return {
    type: 'DATE_HISTOGRAM',
    field,
    alias,
    unit,
    label,
    ...(dense ? { dense } : {}),
  };
}

// ---------------------------------------------------------------- 骨架

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
  definitionId: string,
  id: string,
  title: string,
  config: ViewInstance['config'],
  scope: ViewInstance['scope'] = 'shared',
): ViewInstance {
  return { id, definitionId, title, scope, revision: '1', config };
}

// ================================================================ 订单工作台

/** 客服读一张单的列：单号、何时、谁、买了什么、到哪一步、付了多少、留言。 */
const ORDER_COLUMNS = [
  'state.orderNo',
  'firstEventTime',
  'state.buyer.nick',
  'state.buyer.level',
  'state.items',
  'state.status',
  'state.amounts.paidAmount',
  'state.channel',
  'state.warehouse',
  'state.remark',
];

function orderRecords(
  filter: FilterTree,
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter,
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    // 本页与全部两行：实付合计，和这批单最早、最晚的下单时间。
    summaries: [
      { field: 'state.amounts.paidAmount', fn: 'SUM' },
      { field: 'firstEventTime', fn: 'MIN' },
    ],
    table: {
      columns: ORDER_COLUMNS.map(field =>
        field === 'state.orderNo' ? { field, pinned: true } : { field },
      ),
    },
    // 手机上客服按卡片翻：单号作标题，买家、状态与钱在前。
    card: {
      title: 'state.orderNo',
      fields: [
        'state.buyer.nick',
        'state.status',
        'state.items',
        'state.amounts.paidAmount',
        'firstEventTime',
        'state.remark',
      ],
    },
    ...overrides,
  };
}

/**
 * 订单工作台的系统视图：客服与运营每天打开的几张。
 *
 * 「发货超时」是值班队列（每 30 秒刷新）：待发货、付款 48 小时仍未发出、不是
 * 预售的单，最早付款的在前。钉住的这一刻里它有 11 张，**全在华东（嘉兴）仓**
 * ——A7 分拣线故障卡住的包裹。
 */
export const ORDER_SYSTEM_VIEWS: SystemView[] = [
  {
    id: 'ship-overdue',
    title: '发货超时',
    config: orderRecords(
      and(
        leaf('state.status', 'IN', ['PAID', 'PARTIALLY_SHIPPED']),
        leaf('state.shipSlaBreached', 'EQ', true),
        { op: 'nor', children: [leaf('state.tags', 'IN', ['PRESALE'])] },
      ),
      {
        filterMode: 'advanced',
        refresh: { interval: 30 },
        sort: [{ field: 'state.timing.paidAt', direction: 'ASC' }],
        summaries: [{ field: 'state.amounts.paidAmount', fn: 'SUM' }],
        table: {
          columns: [
            { field: 'state.orderNo', pinned: true },
            { field: 'state.timing.paidAt' },
            { field: 'state.timing.shipDueAt' },
            { field: 'state.warehouse' },
            { field: 'state.buyer.nick' },
            { field: 'state.items' },
            { field: 'state.amounts.paidAmount' },
            { field: 'state.tags' },
            { field: 'state.remark' },
          ],
        },
      },
    ),
  },
  {
    id: 'all',
    title: '全部订单',
    config: orderRecords(and()),
  },
  {
    id: 'yesterday',
    title: '昨日订单',
    config: orderRecords(and(preset('firstEventTime', 'yesterday'))),
  },
  {
    // 标记同时有「礼品」和「加急」：数组的「全都包含」。
    id: 'gift-urgent',
    title: '礼品加急',
    config: orderRecords(
      and(leaf('state.tags', 'CONTAINS_ALL', ['GIFT', 'URGENT'])),
      {
        table: {
          columns: [
            { field: 'state.orderNo', pinned: true },
            { field: 'firstEventTime' },
            { field: 'state.buyer.nick' },
            { field: 'state.items' },
            { field: 'state.status' },
            { field: 'state.tags' },
            { field: 'state.remark' },
          ],
        },
      },
    ),
  },
  {
    id: 'closed',
    title: '全额退款关闭',
    config: orderRecords(and(leaf('state.status', 'IN', ['CLOSED'])), {
      summaries: [
        { field: 'state.amounts.paidAmount', fn: 'SUM' },
        { field: 'state.amounts.refundedAmount', fn: 'SUM' },
      ],
    }),
  },
];

/** 订单工作台里的共享与个人视图。 */
export const ORDER_WORKBENCH_VIEWS: ViewInstance[] = [
  // 客服组共享：留言里提到改地址的单（全文搜索框）。
  shared(
    RETAIL_ORDERS,
    'orders-address-change',
    '留言提到改地址',
    orderRecords(and(leaf('keyword', 'SEARCH', '改地址'))),
  ),
  // 品控组共享：近 3 个月卖出过竹纤维浴巾 70×140 · 米白、而且这一行退过款的
  // 单——商品行上的元素匹配（同一行同时满足两个条件）。分析工作台「退款率最高
  // 的商品」看到的离群值，追到单子是这张视图：按展开的商品行分组的分析还不能
  // 「查看这些记录」（6.4）。
  shared(
    RETAIL_ORDERS,
    'orders-towel-refunds',
    '浴巾退款单（近 3 个月）',
    orderRecords(
      and(
        leaf('state.items', 'ELEMENT_MATCH', {
          op: 'and',
          children: [
            {
              field: 'state.items.title',
              operator: 'EQ',
              value: BATH_TOWEL_TITLE,
            },
            { field: 'state.items.refundedAmount', operator: 'GT', value: 0 },
          ],
        }),
        recent('firstEventTime', 3, 'month'),
      ),
      {
        summaries: [
          { field: 'state.amounts.paidAmount', fn: 'SUM' },
          { field: 'state.amounts.refundedAmount', fn: 'SUM' },
        ],
      },
    ),
  ),
  // 个人：我跟的大客户——黑卡「欧阳*」，按买家（远程搜索的引用字段）筛。
  shared(
    RETAIL_ORDERS,
    'orders-my-vip',
    '我跟的大客户',
    orderRecords(
      and(
        leaf('state.buyer.id', 'IN', {
          items: [{ id: 'M100002', label: '欧阳*（黑卡 · 南昌市 · M100002）' }],
        }),
      ),
      { layout: 'card' },
    ),
    'personal',
  ),
];

// ================================================================ 分析工作台

const ANALYSTS = RETAIL_ORDER_ANALYSIS;

/**
 * 「本月至今」与「上月同期」：钉住的「现在」是 9 月 22 日 10 点，上月同期就
 * 是 8 月 1 日到 8 月 22 日 10 点。相对日期能说「本月」「上月」，说不出「上
 * 月同期」（与去年同期一样是 Q59 那一类），所以上月同期写成绝对时刻——故事
 * 的时钟钉住，这张视图总是对的；真实产品里它会过期（6.4）。
 */
const THIS_MONTH = and(preset('firstEventTime', 'thisMonth'));
const SAME_PERIOD_LAST_MONTH = and(
  leaf('firstEventTime', 'BETWEEN', {
    type: 'absolute',
    from: '2026-08-01T00:00:00+08:00',
    to: '2026-08-22T10:00:00+08:00',
  }),
);

/** 已付款的单：退款率、超时率这些「按实付算」的口径只数它们。 */
const PAID_ONLY = leaf('state.timing.paidAt', 'IS_NOT_NULL', null);

export const ANALYSIS_VIEWS: ViewInstance[] = [
  // A-01：本月 GMV 较上月同期。指标卡，对比写百分比。
  shared(
    ANALYSTS,
    'a01-gmv-mtd',
    '本月 GMV（较上月同期）',
    analysis({
      groups: [],
      metrics: [
        sum('gmv', GMV, '本月至今 GMV', THIS_MONTH),
        sum('gmvLast', GMV, '上月同期 GMV', SAME_PERIOD_LAST_MONTH),
      ],
      chart: {
        type: 'metric',
        metric: {
          metric: 'gmv',
          compare: { metric: 'gmvLast', mode: 'percent' },
        },
      },
    }),
  ),
  // A-01：本月的五个数——GMV、实付、订单数、买家数、客单价。
  shared(
    ANALYSTS,
    'a01-month-overview',
    '本月经营概况',
    analysis({
      filter: THIS_MONTH,
      groups: [],
      metrics: [
        gmv(),
        sum('paid', PAID, '实付'),
        orders(),
        {
          alias: 'buyers',
          type: 'DISTINCT_COUNT',
          label: '买家数',
          expression: { type: 'FIELD', field: 'state.buyer.id' },
        },
        aov,
      ],
      layout: 'table',
      chart: { type: 'metric', metric: { metric: 'gmv' } },
    }),
  ),
  // A-02：25 个月的日 GMV。缩放到双 11 那一周（滚轮或底部滑条），7 日移动平均
  // 压住噪声，平均线与峰谷一眼看到两次双 11、两次 618 和春节的谷底。
  shared(
    ANALYSTS,
    'a02-daily-gmv',
    '日 GMV 走势（近 25 个月）',
    analysis({
      // 截至昨日：今天才过了 10 个小时，放进来就是最后一个「最低点」。
      filter: and(
        leaf('firstEventTime', 'LTE', { type: 'preset', preset: 'yesterday' }),
      ),
      groups: [byDate('firstEventTime', 'day', 'DAY', '日期', true)],
      metrics: [gmv()],
      sort: [{ alias: 'day', direction: 'ASC' }],
      limit: 1000,
      chart: {
        type: 'line',
        cartesian: {
          x: 'day',
          series: [{ metric: 'gmv' }],
          derived: [{ kind: 'moving-average', metric: 'gmv', window: 7 }],
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
        legend: 'top',
      },
    }),
  ),
  // A-02：月 GMV（柱）与客单价（线，右轴）。
  shared(
    ANALYSTS,
    'a02-monthly-gmv-aov',
    '月 GMV 与客单价',
    analysis({
      groups: [byDate('firstEventTime', 'month', 'MONTH', '月份')],
      metrics: [gmv(), orders(), aov],
      sort: [{ alias: 'month', direction: 'ASC' }],
      chart: {
        type: 'combo',
        cartesian: {
          x: 'month',
          series: [
            { metric: 'gmv', type: 'bar', axis: 'left' },
            { metric: 'aov', type: 'line', axis: 'right', smooth: true },
          ],
          yAxis: { right: { label: '客单价', min: 150 } },
        },
        legend: 'top',
      },
    }),
  ),
  // A-03：本月至今较上月同期，GMV 的变化由哪些渠道带来——每个渠道两根柱，
  // 表格里多一列「GMV 变化」。瀑布画不了这个：它只收可加的指标，而变化是两
  // 个指标之差（派生指标），引擎拒绝（6.4）。
  shared(
    ANALYSTS,
    'a03-channel-change',
    '本月 GMV 较上月同期（分渠道）',
    analysis({
      groups: [byTerms('state.channel', 'channel')],
      metrics: [
        sum('gmv', GMV, '本月至今', THIS_MONTH),
        sum('gmvLast', GMV, '上月同期', SAME_PERIOD_LAST_MONTH),
        {
          alias: 'delta',
          type: 'DERIVED',
          label: 'GMV 变化',
          expression: {
            type: 'BINARY',
            operator: 'SUBTRACT',
            left: { type: 'METRIC_REF', metric: 'gmv' },
            right: { type: 'METRIC_REF', metric: 'gmvLast' },
          },
        },
      ],
      sort: [{ alias: 'delta', direction: 'DESC' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'channel',
          series: [{ metric: 'gmvLast' }, { metric: 'gmv' }],
        },
        legend: 'top',
      },
    }),
  ),
  // 本月净销售额（实付 − 已退，每张单上的公式再求和）由各渠道一段段累加而
  // 成：瀑布的每一段是一个渠道，最后一根是合计。
  shared(
    ANALYSTS,
    'a03-net-sales-waterfall',
    '本月净销售额的渠道构成',
    analysis({
      filter: THIS_MONTH,
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
            left: { type: 'FIELD', field: PAID },
            right: { type: 'FIELD', field: REFUNDED },
          },
        },
      ],
      sort: [{ alias: 'net', direction: 'DESC' }],
      chart: {
        type: 'waterfall',
        waterfall: { x: 'channel', value: 'net', total: true },
      },
    }),
  ),
  // A-04：近 12 个月的实付由哪些品类构成：一级类目套二级类目的矩形树图。
  shared(
    ANALYSTS,
    'a04-category-treemap',
    '品类构成（近 12 个月实付）',
    analysis({
      filter: and(recent('firstEventTime', 12, 'month')),
      elements: [{ path: 'state.items' }],
      groups: [
        byTerms('state.items.category1', 'category1'),
        byTerms('state.items.category2', 'category2'),
      ],
      metrics: [sum('paid', 'state.items.payAmount', '实付')],
      sort: [{ alias: 'paid', direction: 'DESC' }],
      chart: {
        type: 'treemap',
        treemap: { parent: 'category1', category: 'category2', value: 'paid' },
      },
    }),
  ),
  // A-05：渠道结构按月的变化——直播从 6% 涨到 24%，PC 在萎缩。
  shared(
    ANALYSTS,
    'a05-channel-mix',
    '渠道结构（按月 GMV 占比）',
    analysis({
      groups: [
        byDate('firstEventTime', 'month', 'MONTH', '月份'),
        byTerms('state.channel', 'channel'),
      ],
      metrics: [gmv()],
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
  ),
  // A-06：哪些省份买得多。
  shared(
    ANALYSTS,
    'a06-provinces',
    '省份 GMV 前 15',
    analysis({
      groups: [byTerms('state.address.province', 'province')],
      metrics: [gmv(), orders()],
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
  ),
  // A-06：城市等级 × 渠道。空值单独一组（托盘加维度时的默认）：旧版小程序
  // 没报城市的 88 张单（A5）自成一行「（空）」，且只落在「微信小程序」那一列。
  shared(
    ANALYSTS,
    'a06-tier-channel',
    '城市等级 × 渠道',
    analysis({
      groups: [
        {
          ...byTerms('state.address.cityTier', 'tier'),
          missingKey: DEFAULT_MISSING_KEY,
        } as AnalysisGroup,
        byTerms('state.channel', 'channel'),
      ],
      metrics: [orders()],
      sort: [{ alias: 'orders', direction: 'DESC' }],
      chart: {
        type: 'heatmap',
        heatmap: { x: 'channel', y: 'tier', value: 'orders', scale: 'log' },
      },
    }),
  ),
  // A-07：哪些商品的退款率异常。展开商品行，近 3 个月件数不少于 30 的商品里
  // 退款率最高的 10 个——竹纤维浴巾 70×140 · 米白 远在最上面（A1，约 26%，
  // 其余在 13% 以下）。点它「查看这些记录」就是这些单。
  shared(
    ANALYSTS,
    'a07-refund-outliers',
    '退款率最高的商品（近 3 个月）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      elements: [{ path: 'state.items' }],
      groups: [byTerms('state.items.title', 'title', '商品')],
      metrics: [
        sum('qty', 'state.items.qty', '件数'),
        sum('paid', 'state.items.payAmount', '实付'),
        sum('refunded', 'state.items.refundedAmount', '已退'),
        ratio('refundRate', '退款率（%）', 'refunded', 'paid', 100),
      ],
      having: { type: 'CONDITION', metric: 'qty', operator: 'GTE', value: 30 },
      sort: [{ alias: 'refundRate', direction: 'DESC' }],
      limit: 10,
      chart: {
        type: 'bar',
        cartesian: {
          x: 'title',
          series: [{ metric: 'refundRate' }],
          orientation: 'horizontal',
        },
        legend: 'none',
      },
    }),
  ),
  // A-08：谁是大客户——近 12 个月实付前 20 的买家，带合计行。按会员号分组，
  // 昵称与等级是「任一值」（一个会员号只有一个昵称）；合计行上这两格空着——
  // 整个范围的「任一值」不属于任何人。
  shared(
    ANALYSTS,
    'a08-top-buyers',
    '大客户 Top 20',
    analysis({
      filter: and(recent('firstEventTime', 12, 'month')),
      groups: [byTerms('state.buyer.id', 'buyer', '会员号')],
      metrics: [
        {
          alias: 'nick',
          type: 'ANY',
          field: 'state.buyer.nick',
          label: '昵称',
        },
        {
          alias: 'level',
          type: 'ANY',
          field: 'state.buyer.level',
          label: '会员等级',
        },
        sum('paid', PAID, '实付'),
        {
          alias: 'checkouts',
          type: 'DISTINCT_COUNT',
          label: '下单次数',
          expression: { type: 'FIELD', field: 'state.parentOrderNo' },
        },
      ],
      sort: [{ alias: 'paid', direction: 'DESC' }],
      limit: 20,
      layout: 'table',
      table: { columns: [], totals: true },
      chart: {
        type: 'bar',
        cartesian: { x: 'buyer', series: [{ metric: 'paid' }] },
      },
    }),
  ),
  // A-09：下单到完成，每一步各自的条件；转化按上一步算。付款这一步漏得最多。
  shared(
    ANALYSTS,
    'a09-funnel',
    '下单到完成的漏斗',
    analysis({
      groups: [],
      metrics: [
        count('placed', '下单'),
        count('paid', '付款', and(PAID_ONLY)),
        count(
          'shipped',
          '发货',
          and(leaf('state.timing.shippedAt', 'IS_NOT_NULL', null)),
        ),
        count(
          'signed',
          '签收',
          and(leaf('state.timing.signedAt', 'IS_NOT_NULL', null)),
        ),
        count(
          'completed',
          '交易完成',
          and(leaf('state.timing.completedAt', 'IS_NOT_NULL', null)),
        ),
      ],
      chart: {
        type: 'funnel',
        funnel: {
          stages: {
            from: 'metrics',
            items: [
              { metric: 'placed' },
              { metric: 'paid' },
              { metric: 'shipped' },
              { metric: 'signed' },
              { metric: 'completed' },
            ],
          },
          conversion: 'previous',
        },
      },
    }),
  ),
  // A-10：买家什么时候下单——星期 × 时段（读模型字段，原因见定义）。
  shared(
    ANALYSTS,
    'a10-weekday-hour',
    '下单时段热力（星期 × 时段）',
    analysis({
      groups: [
        byTerms('state.placedHour', 'hour', '时段（点）'),
        byTerms('state.placedWeekday', 'weekday', '星期'),
      ],
      metrics: [orders()],
      sort: [
        { alias: 'weekday', direction: 'ASC' },
        { alias: 'hour', direction: 'ASC' },
      ],
      limit: 1000,
      chart: {
        type: 'heatmap',
        heatmap: { x: 'hour', y: 'weekday', value: 'orders' },
      },
    }),
  ),
  // A-10（A4）：2025 年双 11 零点到两点，各支付方式的超时取消率——云闪付
  // 5 单全部超时，支付宝 20%，微信支付没有。
  shared(
    ANALYSTS,
    'a10-double11-midnight',
    '双 11 零点：各支付方式的超时率',
    analysis({
      filter: and(
        leaf('firstEventTime', 'BETWEEN', {
          type: 'absolute',
          from: '2025-11-11T00:00:00+08:00',
          to: '2025-11-11T02:00:00+08:00',
        }),
      ),
      groups: [byTerms('state.payment.method', 'method')],
      metrics: [
        orders(),
        count(
          'timedOut',
          '超时取消',
          and(leaf('state.cancelReason', 'IN', ['PAYMENT_TIMEOUT'])),
        ),
        ratio('timeoutRate', '超时率（%）', 'timedOut', 'orders', 100),
      ],
      // 两小时里只有一两单的支付方式，一单超时就是 100%：只看至少 3 单的。
      having: {
        type: 'CONDITION',
        metric: 'orders',
        operator: 'GTE',
        value: 3,
      },
      sort: [{ alias: 'timeoutRate', direction: 'DESC' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'method', series: [{ metric: 'timeoutRate' }] },
        legend: 'none',
      },
    }),
  ),
  // A-11：大促到底拉动了多少——2025 年双 11 前后的日 GMV。活动前后的平均线
  // 与峰谷点：11 月 11 日当天是平日的好几倍。
  shared(
    ANALYSTS,
    'a11-double11',
    '2025 双 11 前后的日 GMV',
    analysis({
      filter: and(days('firstEventTime', '2025-10-15', '2025-11-20')),
      groups: [byDate('firstEventTime', 'day', 'DAY', '日期', true)],
      metrics: [gmv(), orders(), aov],
      sort: [{ alias: 'day', direction: 'ASC' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'day',
          series: [{ metric: 'gmv' }],
          referenceLines: [
            {
              axis: 'left',
              statistic: 'median',
              metric: 'gmv',
              label: '中位数',
            },
          ],
          extremes: true,
        },
        // 37 根柱各写一个数就糊成一片：峰谷点与中位线已经说了要说的。
        labels: false,
        legend: 'none',
      },
    }),
  ),
  // A-11：各活动的客单价与优惠力度（优惠占原价的比例）。不在活动期的单是空值
  // 那一组「（空）」。
  shared(
    ANALYSTS,
    'a11-activities',
    '各活动的客单价与优惠力度',
    analysis({
      groups: [
        {
          ...byTerms('state.promotion.activityId', 'activity'),
          missingKey: DEFAULT_MISSING_KEY,
        } as AnalysisGroup,
      ],
      metrics: [
        gmv(),
        orders(),
        aov,
        sum('list', 'state.amounts.listAmount', '原价合计'),
        {
          alias: 'discount',
          type: 'NUMERIC',
          function: 'SUM',
          label: '优惠',
          expression: {
            type: 'BINARY',
            operator: 'SUBTRACT',
            left: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'state.amounts.listAmount' },
              right: { type: 'FIELD', field: 'state.amounts.freight' },
            },
            right: { type: 'FIELD', field: GMV },
          },
        },
        ratio('discountRate', '优惠占比（%）', 'discount', 'list', 100),
      ],
      sort: [{ alias: 'gmv', direction: 'DESC' }],
      layout: 'table',
      // 先读结论（GMV、单数、客单价、优惠占比），算它们用的原价与优惠在后。
      table: {
        columns: ['activity', 'gmv', 'orders', 'aov', 'discountRate'].map(
          alias => ({ alias }),
        ),
      },
      chart: {
        type: 'bar',
        cartesian: { x: 'activity', series: [{ metric: 'aov' }] },
      },
    }),
  ),
  // A-12：每周的发货超时率，5% 是红线。春节停运那一周（A6）约 62%，两次双
  // 11 那一周约 22%。
  shared(
    ANALYSTS,
    'a12-weekly-sla',
    '每周发货超时率',
    analysis({
      filter: and(PAID_ONLY),
      groups: [byDate('state.timing.paidAt', 'week', 'WEEK', '付款周', true)],
      metrics: [
        count('paid', '已付款'),
        count('late', '超时', and(leaf('state.shipSlaBreached', 'EQ', true))),
        ratio('lateRate', '超时率（%）', 'late', 'paid', 100),
      ],
      sort: [{ alias: 'week', direction: 'ASC' }],
      limit: 200,
      chart: {
        type: 'line',
        cartesian: {
          x: 'week',
          series: [{ metric: 'lateRate' }],
          referenceLines: [{ axis: 'left', value: 5, label: '红线 5%' }],
          extremes: true,
        },
        legend: 'none',
      },
    }),
  ),
  // A-12：各仓付款到发货的小时数，中位数与 90 分位（近似值，写「≈」）。
  shared(
    ANALYSTS,
    'a12-warehouse-hours',
    '各仓付款到发货（P50 / P90）',
    analysis({
      filter: and(recent('firstEventTime', 3, 'month')),
      groups: [byTerms('state.warehouse', 'warehouse')],
      metrics: [
        {
          alias: 'p50',
          type: 'PERCENTILE',
          percentile: 50,
          label: '中位数',
          expression: { type: 'FIELD', field: 'state.payToShipHours' },
        },
        {
          alias: 'p90',
          type: 'PERCENTILE',
          percentile: 90,
          label: '90 分位',
          expression: { type: 'FIELD', field: 'state.payToShipHours' },
        },
      ],
      sort: [{ alias: 'p90', direction: 'DESC' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'p50' }, { metric: 'p90' }],
          referenceLines: [{ axis: 'left', value: 48, label: '48 小时' }],
        },
        legend: 'top',
      },
    }),
  ),
  // A-14：直播间的优惠给多了，是不是退得也多。每天一个点（单数不少于 8），
  // 横轴优惠占比、纵轴退款率、点的大小是单数：3 月 8 日叠加券那一天（A3）
  // 在最右边，远离其他点。
  shared(
    ANALYSTS,
    'a14-discount-refund',
    '直播间：优惠占比 × 退款率（每天一点）',
    analysis({
      filter: and(
        leaf('state.channel', 'IN', ['LIVE']),
        recent('firstEventTime', 12, 'month'),
      ),
      groups: [byDate('firstEventTime', 'day', 'DAY', '日期')],
      metrics: [
        orders(),
        sum('list', 'state.amounts.listAmount', '原价合计'),
        {
          alias: 'discount',
          type: 'NUMERIC',
          function: 'SUM',
          label: '优惠',
          expression: {
            type: 'BINARY',
            operator: 'SUBTRACT',
            left: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'state.amounts.listAmount' },
              right: { type: 'FIELD', field: 'state.amounts.freight' },
            },
            right: { type: 'FIELD', field: GMV },
          },
        },
        sum('paid', PAID, '实付'),
        sum('refunded', REFUNDED, '已退'),
        ratio('discountRate', '优惠占比（%）', 'discount', 'list', 100),
        ratio('refundRate', '退款率（%）', 'refunded', 'paid', 100),
      ],
      having: {
        type: 'CONDITION',
        metric: 'orders',
        operator: 'GTE',
        value: 8,
      },
      sort: [{ alias: 'day', direction: 'ASC' }],
      limit: 400,
      chart: {
        type: 'scatter',
        scatter: {
          category: 'day',
          x: 'discountRate',
          y: 'refundRate',
          size: 'orders',
        },
      },
    }),
  ),
  // A-15：新客与老客各贡献多少（按月堆叠的面积）：按「新客」拆开，图例写
  // 「新客：是」「新客：否」。
  shared(
    ANALYSTS,
    'a15-new-returning',
    '新客与老客的 GMV',
    analysis({
      groups: [
        byDate('firstEventTime', 'month', 'MONTH', '月份'),
        byTerms('state.buyer.isNewBuyer', 'isNew'),
      ],
      metrics: [gmv()],
      sort: [{ alias: 'month', direction: 'ASC' }],
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
  ),
  // A-16：价格带——件数集中在 100 元以下，实付集中在 100～500 元。
  shared(
    ANALYSTS,
    'a16-price-bands',
    '成交单价分布（每 100 元一档）',
    analysis({
      elements: [{ path: 'state.items' }],
      groups: [
        {
          type: 'HISTOGRAM',
          field: 'state.items.salePrice',
          alias: 'price',
          interval: 100,
          label: '成交单价',
        },
      ],
      metrics: [
        sum('qty', 'state.items.qty', '件数'),
        sum('paid', 'state.items.payAmount', '实付'),
      ],
      sort: [{ alias: 'price', direction: 'ASC' }],
      chart: {
        type: 'combo',
        cartesian: {
          x: 'price',
          series: [
            { metric: 'qty', type: 'bar', axis: 'left' },
            { metric: 'paid', type: 'line', axis: 'right' },
          ],
        },
        legend: 'top',
      },
    }),
  ),
  // A-17：买家用什么付款。
  shared(
    ANALYSTS,
    'a17-payment-methods',
    '支付方式构成',
    analysis({
      filter: and(PAID_ONLY),
      groups: [byTerms('state.payment.method', 'method')],
      metrics: [sum('paid', PAID, '实付'), orders()],
      sort: [{ alias: 'paid', direction: 'DESC' }],
      chart: {
        type: 'pie',
        pie: { category: 'method', value: 'paid', donut: true },
      },
    }),
  ),
];

// ================================================================ 会员分析

export const MEMBER_ANALYSIS_VIEWS: ViewInstance[] = [
  // A-08：长尾有多长——买过几次的人各有多少。一多半只买过一次；按次数分组
  // （一次一组，前 20 组），尾巴长到几百次，结果条会说还有更多的组。累计占比
  // 画不了：「累计」只沿时间轴算（6.4）。
  shared(
    RETAIL_MEMBERS,
    'a08-purchase-count',
    '购买次数分布',
    analysis({
      filter: and(leaf('state.orderCount', 'GTE', 1)),
      groups: [byTerms('state.orderCount', 'times', '购买次数')],
      metrics: [count('members', '人数')],
      sort: [{ alias: 'times', direction: 'ASC' }],
      limit: 20,
      chart: {
        type: 'bar',
        cartesian: { x: 'times', series: [{ metric: 'members' }] },
        legend: 'none',
      },
    }),
  ),
  // A-18：首单月 × 复购率（买过两次及以上的人 ÷ 人数）。大促拉来的新客复购
  // 率低于平时拉来的。
  shared(
    RETAIL_MEMBERS,
    'a18-repurchase',
    '按首单月的复购率',
    analysis({
      filter: and(
        leaf('state.firstOrderAt', 'BETWEEN', {
          type: 'absolute',
          from: '2024-09-01T00:00:00+08:00',
          to: '2026-03-31T23:59:59.999+08:00',
        }),
      ),
      groups: [byDate('state.firstOrderAt', 'month', 'MONTH', '首单月')],
      metrics: [
        count('members', '新客'),
        count('repeat', '复购', and(leaf('state.orderCount', 'GTE', 2))),
        ratio('rate', '复购率（%）', 'repeat', 'members', 100),
      ],
      sort: [{ alias: 'month', direction: 'ASC' }],
      chart: {
        type: 'combo',
        cartesian: {
          x: 'month',
          series: [
            { metric: 'members', type: 'bar', axis: 'left' },
            { metric: 'rate', type: 'line', axis: 'right' },
          ],
        },
        legend: 'top',
      },
    }),
  ),
];

// ================================================================ 售后工作台

function afterSaleRecords(
  filter: FilterTree,
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter,
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'state.requestedAt', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [
      { field: 'state.requestedAmount', fn: 'SUM' },
      { field: 'state.refundedAmount', fn: 'SUM' },
    ],
    table: {
      columns: [
        { field: 'state.afterSaleNo', pinned: true },
        { field: 'state.requestedAt' },
        { field: 'state.type' },
        { field: 'state.reason' },
        { field: 'state.status' },
        { field: 'state.title' },
        { field: 'state.requestedAmount' },
        { field: 'state.refundedAmount' },
        { field: 'state.orderNo' },
        { field: 'state.channel' },
      ],
    },
    card: {
      title: 'state.afterSaleNo',
      fields: [
        'state.title',
        'state.reason',
        'state.status',
        'state.refundedAmount',
        'state.requestedAt',
      ],
    },
    ...overrides,
  };
}

export const AFTER_SALE_SYSTEM_VIEWS: SystemView[] = [
  { id: 'all', title: '全部售后', config: afterSaleRecords(and()) },
  {
    id: 'pending',
    title: '待处理',
    config: afterSaleRecords(
      and(leaf('state.status', 'IN', ['REQUESTED', 'APPROVED'])),
      { sort: [{ field: 'state.requestedAt', direction: 'ASC' }] },
    ),
  },
  {
    // A-17：为什么退——售后理由的构成。
    id: 'by-reason',
    title: '售后理由构成',
    config: analysis({
      filter: and(recent('state.requestedAt', 12, 'month')),
      groups: [byTerms('state.reason', 'reason')],
      metrics: [
        count('cases', '售后单'),
        sum('refunded', 'state.refundedAmount', '实退金额'),
      ],
      sort: [{ alias: 'cases', direction: 'DESC' }],
      chart: {
        type: 'pie',
        pie: { category: 'reason', value: 'cases', donut: true },
      },
    }),
  },
  {
    // 床品的退货以尺寸或颜色不符为主：各类目的理由构成。
    id: 'reason-by-category',
    title: '各类目的售后理由',
    config: analysis({
      filter: and(recent('state.requestedAt', 12, 'month')),
      groups: [
        byTerms('state.category1', 'category'),
        byTerms('state.reason', 'reason'),
      ],
      metrics: [count('cases', '售后单')],
      sort: [{ alias: 'cases', direction: 'DESC' }],
      limit: 1000,
      chart: {
        type: 'bar',
        cartesian: {
          x: 'category',
          splitBy: 'reason',
          series: [{ metric: 'cases', stack: 'reason' }],
          percentStack: true,
          orientation: 'horizontal',
        },
        legend: 'top',
      },
    }),
  },
  {
    // 近 30 天每天退出去的钱，和本期累计。补空桶：没有退款的那天是确知的
    // 0，累计线穿过它。
    id: 'daily-refunds',
    title: '每日退款金额（近 30 天）',
    config: analysis({
      filter: and(recent('state.refundedAt', 30, 'day')),
      groups: [byDate('state.refundedAt', 'day', 'DAY', '退款日', true)],
      metrics: [sum('refunded', 'state.refundedAmount', '实退金额')],
      sort: [{ alias: 'day', direction: 'ASC' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'day',
          series: [{ metric: 'refunded' }],
          derived: [{ kind: 'cumulative', metric: 'refunded' }],
        },
        legend: 'top',
      },
    }),
  },
];

export const AFTER_SALE_WORKBENCH_VIEWS: ViewInstance[] = [
  // 财务对账：本月售后，导出 CSV。
  shared(
    RETAIL_AFTER_SALES,
    'after-sales-this-month',
    '本月售后',
    afterSaleRecords(and(preset('state.requestedAt', 'thisMonth'))),
  ),
  // 品控跟的单子：A1 的浴巾，理由是「质量问题」的售后。5 月 10 日之后突然多
  // 起来——退款率离群那张分析追下来就到这里。
  shared(
    RETAIL_AFTER_SALES,
    'after-sales-towel-quality',
    '竹纤维浴巾的质量投诉',
    afterSaleRecords(
      and(
        leaf('state.title', 'EQ', BATH_TOWEL_TITLE),
        leaf('state.reason', 'IN', ['QUALITY_ISSUE']),
      ),
    ),
    'personal',
  ),
  shared(
    RETAIL_AFTER_SALES,
    'after-sales-towel-monthly',
    '竹纤维浴巾：每月售后理由',
    analysis({
      filter: and(
        leaf('state.title', 'EQ', BATH_TOWEL_TITLE),
        recent('state.requestedAt', 12, 'month'),
      ),
      groups: [
        byDate('state.requestedAt', 'month', 'MONTH', '申请月'),
        byTerms('state.reason', 'reason'),
      ],
      metrics: [count('cases', '售后单')],
      sort: [{ alias: 'month', direction: 'ASC' }],
      limit: 1000,
      chart: {
        type: 'bar',
        cartesian: {
          x: 'month',
          splitBy: 'reason',
          series: [{ metric: 'cases', stack: 'reason' }],
        },
        legend: 'top',
      },
    }),
    'personal',
  ),
];

// ================================================================ 运单宽表

function waybillRecords(
  filter: FilterTree,
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter,
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 50,
    layout: 'table',
    summaries: [
      { field: 'state.itemQty', fn: 'SUM' },
      { field: 'state.weightKg', fn: 'SUM' },
      { field: 'state.shipToSignHours', fn: 'AVG' },
    ],
    table: {
      columns: WAYBILL_COLUMNS.map(field =>
        field === 'state.waybillNo' ? { field, pinned: true } : { field },
      ),
    },
    card: {
      title: 'state.waybillNo',
      fields: [
        'state.carrier',
        'state.status',
        'state.province',
        'state.shippedAt',
        'state.shipToSignHours',
      ],
    },
    ...overrides,
  };
}

const TWO_GUANG = ['广东省', '广西壮族自治区'];

export const WAYBILL_SYSTEM_VIEWS: SystemView[] = [
  { id: 'wide', title: '运单宽表', config: waybillRecords(and()) },
  {
    id: 'in-transit',
    title: '在途包裹',
    config: waybillRecords(and(leaf('state.status', 'IN', ['IN_TRANSIT'])), {
      sort: [{ field: 'state.shippedAt', direction: 'ASC' }],
    }),
  },
  {
    // A2：台风那几天中通在两广的包裹，签收最慢的在前。
    id: 'typhoon',
    title: '台风期间：两广中通',
    config: waybillRecords(
      and(
        leaf('state.carrier', 'IN', ['ZTO']),
        leaf('state.province', 'IN', TWO_GUANG),
        days('state.shippedAt', '2026-07-18', '2026-07-28'),
      ),
      {
        sort: [{ field: 'state.shipToSignHours', direction: 'DESC' }],
        // 查时效的人先看慢了多久、送去哪里。
        table: {
          columns: [
            { field: 'state.waybillNo', pinned: true },
            { field: 'state.shipToSignHours' },
            { field: 'state.province' },
            { field: 'state.city' },
            { field: 'state.shippedAt' },
            { field: 'state.signedAt' },
            { field: 'state.status' },
            { field: 'state.carrier' },
            { field: 'state.orderNo' },
          ],
        },
      },
    ),
  },
  {
    // A-13：哪个承运商在哪几周慢。两广、6 月以来，承运商 × 发货周的平均签收
    // 时长：中通 7 月 20 日那一周约 119 小时（A2），平时 50 多。
    id: 'carrier-weeks',
    title: '两广：承运商 × 周的签收时长',
    config: analysis({
      filter: and(
        leaf('state.province', 'IN', TWO_GUANG),
        days('state.shippedAt', '2026-06-01', '2026-09-20'),
      ),
      groups: [
        byDate('state.shippedAt', 'week', 'WEEK', '发货周'),
        byTerms('state.carrier', 'carrier'),
      ],
      metrics: [
        {
          alias: 'hours',
          type: 'NUMERIC',
          function: 'AVG',
          label: '平均签收时长',
          expression: { type: 'FIELD', field: 'state.shipToSignHours' },
        },
        count('parcels', '包裹数'),
      ],
      sort: [{ alias: 'week', direction: 'ASC' }],
      limit: 1000,
      chart: {
        type: 'heatmap',
        heatmap: { x: 'week', y: 'carrier', value: 'hours' },
      },
    }),
  },
];

// ================================================================ 订单事件流

const EVENT_COLUMNS = ['id', 'createTime', 'aggregateId', 'body', 'version'];

function eventRecords(
  filter: FilterTree,
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter,
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'createTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: EVENT_COLUMNS.map(field => ({ field })) },
    card: { title: 'aggregateId', fields: ['body', 'createTime', 'version'] },
    ...overrides,
  };
}

/** 带某几种事件的事件流：对 `body` 的元素匹配。 */
function carrying(...types: string[]): FilterNode {
  return leaf('body', 'ELEMENT_MATCH', {
    op: 'and',
    children: [
      { field: 'body.bodyType', operator: 'IN', value: types.map(eventType) },
    ],
  });
}

export const ORDER_EVENT_SYSTEM_VIEWS: SystemView[] = [
  { id: 'recent', title: '最近的事件', config: eventRecords(and()) },
  {
    // 模板：填上订单号，按版本读这张单的一生。留空就一张接一张读。
    id: 'history',
    title: '订单历史',
    config: eventRecords(and(leaf('aggregateId', 'EQ', '')), {
      sort: [
        { field: 'aggregateId', direction: 'ASC' },
        { field: 'version', direction: 'ASC' },
      ],
    }),
  },
  {
    id: 'timeouts',
    title: '支付超时',
    config: eventRecords(and(carrying('OrderPaymentTimedOut'))),
  },
  {
    id: 'after-sales',
    title: '售后与退款',
    config: eventRecords(
      and(carrying('AfterSaleRequested', 'RefundSucceeded')),
    ),
  },
  {
    id: 'by-type',
    title: '事件类型分布',
    config: analysis({
      elements: [{ path: 'body' }],
      groups: [byTerms('body.bodyType', 'type', '事件类型')],
      metrics: [count('events', '事件数')],
      sort: [{ alias: 'events', direction: 'DESC' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'type',
          series: [{ metric: 'events' }],
          orientation: 'horizontal',
        },
        legend: 'none',
      },
    }),
  },
  {
    id: 'daily',
    title: '每日事件量',
    config: analysis({
      groups: [byDate('createTime', 'day', 'DAY', '日期', true)],
      metrics: [
        count('streams', '事件流'),
        {
          alias: 'orders',
          type: 'DISTINCT_COUNT',
          label: '涉及订单',
          expression: { type: 'FIELD', field: 'aggregateId' },
        },
      ],
      sort: [{ alias: 'day', direction: 'ASC' }],
      chart: {
        type: 'line',
        cartesian: {
          x: 'day',
          series: [{ metric: 'streams' }, { metric: 'orders' }],
        },
        legend: 'top',
      },
    }),
  },
  {
    id: 'busiest',
    title: '变动最多的订单',
    config: analysis({
      groups: [byTerms('aggregateId', 'order', '订单号')],
      metrics: [
        count('changes', '变动次数'),
        {
          alias: 'latest',
          type: 'NUMERIC',
          function: 'MAX',
          label: '最近一次',
          expression: { type: 'FIELD', field: 'createTime' },
        },
      ],
      sort: [{ alias: 'changes', direction: 'DESC' }],
      limit: 20,
      layout: 'table',
      chart: {
        type: 'bar',
        cartesian: { x: 'order', series: [{ metric: 'changes' }] },
      },
    }),
  },
];

// ================================================================ 定义

export const retailOrdersDefinition = tradeOrderDefinition(
  RETAIL_ORDERS,
  ORDER_SYSTEM_VIEWS,
);
export const retailOrderAnalysisDefinition = tradeOrderDefinition(
  RETAIL_ORDER_ANALYSIS,
);
export const retailAfterSalesDefinition = afterSaleDefinition(
  RETAIL_AFTER_SALES,
  AFTER_SALE_SYSTEM_VIEWS,
);
export const retailMembersDefinition = memberDefinition(RETAIL_MEMBERS);
export const retailWaybillsDefinition = waybillDefinition(
  RETAIL_WAYBILLS,
  WAYBILL_SYSTEM_VIEWS,
);
export const retailOrderEventsDefinition = orderEventsDefinition(
  RETAIL_ORDER_EVENTS,
  ORDER_EVENT_SYSTEM_VIEWS,
);
