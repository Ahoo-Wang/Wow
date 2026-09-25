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
 * 栖木生活的四个聚合、外加订单事件流的定义（docs/scenarios.md 2.3、第 3 节）。
 *
 * 定义是代码：字段、读法、能怎样筛、能怎样分析，跟着应用发布。词用运营与分析
 * 师的词——GMV（应付合计）、实付、已退、净销售额、客单价、退款率、发货及时率
 * ——一词一义：
 *
 * - **GMV** 是下单金额，即子单的应付（`amounts.payableAmount`）之和，含没付成
 *   的单；**实付** 是真正付了的钱（`paidAmount`）；**已退** 是退回去的钱；
 *   **净销售额** = 实付 − 已退，由每条记录上的公式算出，不另存字段。
 * - **客单价** = GMV ÷ 订单数；**退款率** = 已退 ÷ 实付（按金额）；**发货超时率**
 *   = 付款 48 小时后仍未发出的单 ÷ 已付款的单。它们是指标之间的派生，写在视图
 *   里（`views.ts`），不是字段。
 *
 * 读模型派生的字段（下单星期、时段、付款到发货的小时数、是否超时）照 2.3 节写
 * 在字段注释里：Wow 聚合不能按日期部件分组，也不能对两个时刻相减，真实系统由投
 * 影写读模型时算好。
 * ------------------------------------------------------------------------ */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import type {
  AggregationFieldCapability,
  DataViewDefinition,
  FieldDefinition,
  FieldOption,
  FieldTone,
  SystemView,
} from '@ahoo-wang/wow-view-engine';
import {
  ACTIVITIES,
  AFTER_SALE_REASONS,
  AFTER_SALE_TYPES,
  BRANDS,
  CANCEL_REASONS,
  CARRIERS,
  CATEGORIES,
  CHANNELS,
  CITY_TIERS,
  COUPONS,
  INVOICE_TYPES,
  MEMBER_LEVELS,
  ORDER_STATUSES,
  ORDER_TAGS,
  PAYMENT_METHODS,
  SHOPS,
  WAREHOUSES,
  type OrderStatus,
} from './catalog.js';
import { MEMBER_OPTIONS, RETAIL_SOURCES } from './source.js';

const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH, QUARTER } = AggregationDateUnit;

// ---------------------------------------------------------------- 读法

/** 金额以元计，保留两位。 */
export const YUAN = { style: 'currency', currency: 'CNY' } as const;

/** 小时数：付款到发货、发货到签收，保留一位。 */
const HOURS = {
  style: 'unit',
  unit: 'hour',
  maximumFractionDigits: 1,
} as const;

const KG = {
  style: 'unit',
  unit: 'kilogram',
  maximumFractionDigits: 2,
} as const;

function options(
  list: readonly { id: string; name: string }[],
  tones: Partial<Record<string, FieldTone>> = {},
): FieldOption[] {
  return list.map(({ id, name }) => ({
    value: id,
    label: name,
    ...(tones[id] ? { tone: tones[id] } : {}),
  }));
}

export const CHANNEL_OPTIONS = options(CHANNELS);
const SHOP_OPTIONS = options(SHOPS);
const WAREHOUSE_OPTIONS = options(WAREHOUSES);
const CARRIER_OPTIONS = options(CARRIERS);
const LEVEL_OPTIONS = options(MEMBER_LEVELS);
const PAYMENT_OPTIONS = options(PAYMENT_METHODS);
const CANCEL_OPTIONS = options(CANCEL_REASONS);
const INVOICE_OPTIONS = options(INVOICE_TYPES);
const TAG_OPTIONS = options(ORDER_TAGS, {
  URGENT: 'warning',
  RISK_REVIEW: 'danger',
});
const AFTER_SALE_TYPE_OPTIONS = options(AFTER_SALE_TYPES);
const AFTER_SALE_REASON_OPTIONS = options(AFTER_SALE_REASONS);
const TIER_OPTIONS: FieldOption[] = Object.entries(CITY_TIERS).map(
  ([value, label]) => ({ value, label }),
);
const CATEGORY_OPTIONS: FieldOption[] = CATEGORIES.map(({ name }) => ({
  value: name,
  label: name,
}));
const BRAND_OPTIONS: FieldOption[] = BRANDS.map(brand => ({
  value: brand,
  label: brand,
}));
const PRICE_BAND_OPTIONS: FieldOption[] = [
  ['<50', '50 元以下'],
  ['50-100', '50～100 元'],
  ['100-200', '100～200 元'],
  ['200-500', '200～500 元'],
  ['500-1000', '500～1000 元'],
  ['>=1000', '1000 元及以上'],
].map(([value, label]) => ({ value, label }));
const ACTIVITY_OPTIONS: FieldOption[] = ACTIVITIES.map(({ id, name }) => ({
  value: id,
  label: name,
}));
const COUPON_OPTIONS: FieldOption[] = COUPONS.map(({ id, name }) => ({
  value: id,
  label: name,
}));
const WEEKDAY_OPTIONS: FieldOption[] = [
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
  '周日',
].map((label, index) => ({ value: index + 1, label }));

/** 订单状态的语气：等人处理的是提醒，取消是坏消息，签收与完成是好消息。 */
const STATUS_TONES: Record<OrderStatus, FieldTone | undefined> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'warning',
  PARTIALLY_SHIPPED: undefined,
  SHIPPED: undefined,
  SIGNED: 'success',
  COMPLETED: 'success',
  CANCELLED: 'danger',
  CLOSED: 'neutral',
};
const STATUS_OPTIONS = options(ORDER_STATUSES, STATUS_TONES);

function amount(name: string, label: string): FieldDefinition {
  return {
    name,
    label,
    kind: 'number',
    sortable: true,
    numberFormat: YUAN,
    summary: ['SUM', 'AVG', 'MIN', 'MAX'],
  };
}

function enumField(
  name: string,
  label: string,
  choices: FieldOption[],
  extra: Partial<FieldDefinition> = {},
): FieldDefinition {
  return {
    name,
    label,
    kind: 'enum',
    sortable: true,
    options: choices,
    ...extra,
  };
}

function text(
  name: string,
  label: string,
  extra: Partial<FieldDefinition> = {},
): FieldDefinition {
  return { name, label, kind: 'string', sortable: true, ...extra };
}

function time(name: string, label: string): FieldDefinition {
  return {
    name,
    label,
    kind: 'datetime',
    sortable: true,
    summary: ['MIN', 'MAX'],
  };
}

/** 按值分组的字段。 */
function terms(...fields: string[]): AggregationFieldCapability[] {
  return fields.map(field => ({ field, groups: [TERMS], functions: [] }));
}

/** 能汇总、能分区间、能取百分位的数值字段。 */
function numeric(...fields: string[]): AggregationFieldCapability[] {
  return fields.map(field => ({
    field,
    groups: [HISTOGRAM],
    functions: [SUM, AVG, MIN, MAX],
    percentile: true,
  }));
}

/** 能按日期分桶的时间字段。 */
function dated(...fields: string[]): AggregationFieldCapability[] {
  return fields.map(field => ({
    field,
    groups: [DATE_HISTOGRAM],
    functions: [MIN, MAX],
    dateUnits: [QUARTER, MONTH, WEEK, DAY, HOUR],
  }));
}

// ---------------------------------------------------------------- 交易订单

const ORDER_AMOUNTS: [string, string][] = [
  ['state.amounts.listAmount', '原价合计'],
  ['state.amounts.itemDiscount', '单品直降'],
  ['state.amounts.fullReduction', '满减'],
  ['state.amounts.shopCoupon', '店铺券'],
  ['state.amounts.platformCoupon', '平台券'],
  ['state.amounts.pointsDeduct', '积分抵扣'],
  ['state.amounts.freight', '运费'],
  ['state.amounts.payableAmount', '应付（GMV）'],
  ['state.amounts.paidAmount', '实付'],
  ['state.amounts.refundedAmount', '已退'],
];

const ORDER_TIMES: [string, string][] = [
  ['state.timing.paidAt', '付款时间'],
  ['state.timing.shipDueAt', '发货期限'],
  ['state.timing.shippedAt', '发货时间'],
  ['state.timing.signedAt', '签收时间'],
  ['state.timing.completedAt', '完成时间'],
  ['state.timing.cancelledAt', '取消时间'],
];

const ORDER_FIELDS: FieldDefinition[] = [
  text('state.orderNo', '订单号', { cell: 'copyable' }),
  text('state.parentOrderNo', '主单号', { cell: 'copyable' }),
  enumField('state.shopId', '店铺', SHOP_OPTIONS),
  enumField('state.channel', '渠道', CHANNEL_OPTIONS),
  enumField('state.warehouse', '发货仓', WAREHOUSE_OPTIONS),
  {
    // 客服按买家找单：7000 个会员，远程搜昵称、会员号或城市。
    name: 'state.buyer.id',
    label: '买家',
    kind: 'reference',
    remote: MEMBER_OPTIONS,
    sortable: true,
  },
  // 快照信封的 `ownerId` 就是买家的会员号：按它精确找一个人的全部订单。
  { name: 'ownerId', label: '买家会员号', kind: 'ownerId' },
  text('state.buyer.nick', '买家昵称'),
  enumField('state.buyer.level', '会员等级', LEVEL_OPTIONS),
  {
    // 只在这个买家第一张已付款的子单上为真。
    name: 'state.buyer.isNewBuyer',
    label: '新客',
    kind: 'boolean',
    sortable: true,
  },
  enumField('state.status', '订单状态', STATUS_OPTIONS, { cell: 'status' }),
  enumField(
    'state.afterSaleStatus',
    '售后状态',
    [
      { value: 'NONE', label: '无售后' },
      { value: 'IN_PROGRESS', label: '售后中', tone: 'warning' },
      { value: 'FINISHED', label: '售后完成' },
    ],
    { cell: 'status' },
  ),
  enumField('state.cancelReason', '取消原因', CANCEL_OPTIONS),
  {
    // 一张子单的商品行：一格里读出每行的商品名；按商品筛选是元素匹配，按商
    // 品分析展开数组、以订单行为计数单位。
    name: 'state.items',
    label: '商品',
    kind: 'elementMatch',
    operators: ['ELEMENT_MATCH'],
    elementTitle: 'title',
    elements: [
      { name: 'title', label: '商品', kind: 'string' },
      { name: 'skuId', label: 'SKU', kind: 'string' },
      {
        name: 'category1',
        label: '一级类目',
        kind: 'enum',
        options: CATEGORY_OPTIONS,
      },
      { name: 'category2', label: '二级类目', kind: 'string' },
      { name: 'brand', label: '品牌', kind: 'enum', options: BRAND_OPTIONS },
      {
        name: 'priceBand',
        label: '价格带',
        kind: 'enum',
        options: PRICE_BAND_OPTIONS,
      },
      { name: 'qty', label: '件数', kind: 'number' },
      {
        name: 'salePrice',
        label: '成交单价',
        kind: 'number',
        numberFormat: YUAN,
      },
      { name: 'payAmount', label: '实付', kind: 'number', numberFormat: YUAN },
      { name: 'refundedQty', label: '退货件数', kind: 'number' },
      {
        name: 'refundedAmount',
        label: '已退',
        kind: 'number',
        numberFormat: YUAN,
      },
    ],
  },
  // 商品名另作一个根字段：搜索只能点名根字段，而客服按商品名找单时要搜的
  // 正是每一行的标题。Wow 按 MongoDB 的路径读它，任一行的标题匹配，这张单就
  // 匹配——它是文档里真实存在的路径（第 4 批，首页的「搜索订单」用它）。
  text('state.items.title', '商品名', { sortable: false }),
  ...ORDER_AMOUNTS.map(([name, label]) => amount(name, label)),
  enumField('state.payment.method', '支付方式', PAYMENT_OPTIONS),
  {
    name: 'state.payment.installments',
    label: '分期期数',
    kind: 'number',
    sortable: true,
  },
  enumField('state.promotion.activityId', '活动', ACTIVITY_OPTIONS),
  {
    name: 'state.promotion.coupons',
    label: '用券',
    kind: 'array',
    options: COUPON_OPTIONS,
    cell: 'tags',
  },
  text('state.address.province', '省份'),
  text('state.address.city', '城市'),
  enumField('state.address.cityTier', '城市等级', TIER_OPTIONS),
  text('state.address.district', '区县'),
  {
    // 部分发货就是多个包裹，到得有先有后；一格里读出每个包裹的运单号。
    name: 'state.packages',
    label: '包裹',
    kind: 'elementMatch',
    operators: ['ELEMENT_MATCH'],
    elementTitle: 'waybillNo',
    elements: [
      { name: 'waybillNo', label: '运单号', kind: 'string' },
      {
        name: 'carrier',
        label: '承运商',
        kind: 'enum',
        options: CARRIER_OPTIONS,
      },
      { name: 'shippedAt', label: '发货时间', kind: 'datetime' },
      { name: 'signedAt', label: '签收时间', kind: 'datetime' },
    ],
  },
  ...ORDER_TIMES.map(([name, label]) => time(name, label)),
  {
    // 读模型派生：下单那一刻是星期几（上海时间），1 是周一。
    ...enumField('state.placedWeekday', '下单星期', WEEKDAY_OPTIONS),
  },
  {
    // 读模型派生：下单那一刻的小时（0～23，上海时间）。
    name: 'state.placedHour',
    label: '下单时段（点）',
    kind: 'number',
    sortable: true,
  },
  {
    // 读模型派生：两个时刻之差，Wow 聚合不能在查询里相减。
    name: 'state.payToShipHours',
    label: '付款到发货',
    kind: 'number',
    sortable: true,
    numberFormat: HOURS,
    summary: ['AVG', 'MIN', 'MAX'],
  },
  {
    name: 'state.shipToSignHours',
    label: '发货到签收',
    kind: 'number',
    sortable: true,
    numberFormat: HOURS,
    summary: ['AVG', 'MIN', 'MAX'],
  },
  {
    // 读模型派生：付款 48 小时后仍未发出（到「现在」为止）。
    name: 'state.shipSlaBreached',
    label: '发货超时',
    kind: 'boolean',
    sortable: true,
  },
  enumField('state.invoice.type', '发票', INVOICE_OPTIONS),
  {
    name: 'state.tags',
    label: '标记',
    kind: 'array',
    options: TAG_OPTIONS,
    cell: 'tags',
  },
  text('state.remark', '买家留言', { cell: 'text', sortable: false }),
  {
    // 客服按留言里的话找单（「改地址」），也能直接贴订单号、昵称或商品名。
    name: 'keyword',
    label: '搜索留言、订单号、昵称或商品名',
    kind: 'search',
    searchFields: [
      'state.remark',
      'state.orderNo',
      'state.buyer.nick',
      'state.items.title',
    ],
    searchMode: 'PHRASE',
  },
  time('firstEventTime', '下单时间'),
  time('eventTime', '最近更新'),
];

const ORDER_GROUPS = [
  {
    id: 'identity',
    label: '订单',
    fields: [
      'state.orderNo',
      'state.parentOrderNo',
      'state.shopId',
      'state.channel',
      'state.warehouse',
      'state.status',
      'state.afterSaleStatus',
      'state.cancelReason',
      'state.tags',
    ],
  },
  {
    id: 'buyer',
    label: '买家',
    fields: [
      'state.buyer.id',
      'ownerId',
      'state.buyer.nick',
      'state.buyer.level',
      'state.buyer.isNewBuyer',
      'state.remark',
      'keyword',
    ],
  },
  { id: 'items', label: '商品', fields: ['state.items', 'state.items.title'] },
  {
    id: 'amount',
    label: '金额与支付',
    fields: [
      ...ORDER_AMOUNTS.map(([name]) => name),
      'state.payment.method',
      'state.payment.installments',
      'state.promotion.activityId',
      'state.promotion.coupons',
      'state.invoice.type',
    ],
  },
  {
    id: 'address',
    label: '收货地',
    fields: [
      'state.address.province',
      'state.address.city',
      'state.address.cityTier',
      'state.address.district',
    ],
  },
  {
    id: 'fulfilment',
    label: '履约',
    fields: [
      'state.packages',
      'state.payToShipHours',
      'state.shipToSignHours',
      'state.shipSlaBreached',
    ],
  },
  {
    id: 'time',
    label: '时间',
    fields: [
      'firstEventTime',
      ...ORDER_TIMES.map(([name]) => name),
      'state.placedWeekday',
      'state.placedHour',
      'eventTime',
    ],
  },
];

const ORDER_ANALYSIS: DataViewDefinition['analysis'] = {
  count: true,
  having: true,
  expressions: true,
  // Wow 的聚合一次最多 10000 组；日粒度 25 个月约 760 组。
  limits: { maxLimit: 1000 },
  fields: [
    ...terms(
      'state.shopId',
      'state.channel',
      'state.warehouse',
      'state.buyer.level',
      'state.buyer.isNewBuyer',
      'state.status',
      'state.afterSaleStatus',
      'state.cancelReason',
      'state.payment.method',
      'state.promotion.activityId',
      'state.address.province',
      'state.address.city',
      'state.address.cityTier',
      'state.invoice.type',
      'state.placedWeekday',
      'state.shipSlaBreached',
    ),
    // 按买家排行，带出昵称与等级（「任一值」）。
    {
      field: 'state.buyer.id',
      groups: [TERMS],
      functions: [],
      distinctCount: true,
    },
    { field: 'state.buyer.nick', groups: [TERMS], functions: [], any: true },
    {
      field: 'state.buyer.level',
      groups: [TERMS],
      functions: [],
      any: true,
    },
    {
      field: 'state.parentOrderNo',
      groups: [],
      functions: [],
      distinctCount: true,
    },
    { field: 'state.placedHour', groups: [TERMS, HISTOGRAM], functions: [] },
    ...numeric(
      ...ORDER_AMOUNTS.map(([name]) => name),
      'state.payToShipHours',
      'state.shipToSignHours',
    ),
    ...dated('firstEventTime', ...ORDER_TIMES.map(([name]) => name)),
  ],
  elements: [
    {
      path: 'state.items',
      aggregations: [
        ...terms('title', 'skuId', 'category1', 'category2', 'brand'),
        { field: 'priceBand', groups: [TERMS], functions: [] },
        { field: 'qty', groups: [], functions: [SUM, AVG, MAX] },
        {
          field: 'salePrice',
          groups: [HISTOGRAM],
          functions: [AVG, MIN, MAX],
          percentile: true,
        },
        { field: 'payAmount', groups: [], functions: [SUM, AVG, MAX] },
        { field: 'refundedQty', groups: [], functions: [SUM] },
        { field: 'refundedAmount', groups: [], functions: [SUM] },
      ],
    },
    // 分析的展开是一条链（一层套一层），不是并列的几条：包裹按承运商、时效
    // 分析走运单读模型（一包一行），这里只展开商品行。
  ],
};

/**
 * 交易订单（子订单）：履约与售后的单位。一次结账是一张主单，按仓库或店铺拆
 * 成子单；GMV 与订单数按子单算，「买家下了几次单」是主单号的去重计数。
 *
 * `id` 与 `views` 由场景给：订单工作台带客服与运营的系统视图，分析工作台只放
 * 分析师存下的分析，两者读同一个数据源。
 */
export function tradeOrderDefinition(
  id: string,
  views: SystemView[] = [],
): DataViewDefinition {
  return {
    id,
    title: '交易订单',
    recordNoun: '子订单',
    kind: 'data',
    source: RETAIL_SOURCES.orders,
    fieldGroups: ORDER_GROUPS,
    fields: ORDER_FIELDS,
    record: {
      rowKey: 'state.orderNo',
      paging: 'paged',
      layouts: ['table', 'card'],
      maxWindow: 10_000,
      // 宿主的「催发货」只对还没发出的单开放，所以每一行都要带上状态。
      rowFields: ['state.status'],
    },
    analysis: ORDER_ANALYSIS,
    views,
  };
}

// ---------------------------------------------------------------- 售后单

export const AFTER_SALE_STATUS_OPTIONS: FieldOption[] = [
  { value: 'REQUESTED', label: '待审核', tone: 'warning' },
  { value: 'APPROVED', label: '待退款', tone: 'warning' },
  { value: 'REJECTED', label: '已驳回', tone: 'danger' },
  { value: 'REFUNDED', label: '已退款', tone: 'success' },
  { value: 'EXCHANGED', label: '已换货', tone: 'success' },
];

/**
 * 售后单：仅退款、退货退款、换货与拒收。指向子单与行；带理由、申请金额、实
 * 退金额，以及申请、审核、退款三个时刻。
 */
export function afterSaleDefinition(
  id: string,
  views: SystemView[] = [],
): DataViewDefinition {
  return {
    id,
    title: '售后单',
    recordNoun: '售后单',
    kind: 'data',
    source: RETAIL_SOURCES.afterSales,
    fieldGroups: [
      {
        id: 'case',
        label: '售后',
        fields: [
          'state.afterSaleNo',
          'state.type',
          'state.reason',
          'state.status',
          'state.qty',
          'state.requestedAmount',
          'state.refundedAmount',
        ],
      },
      {
        id: 'order',
        label: '订单与商品',
        fields: [
          'state.orderNo',
          'state.lineId',
          'state.title',
          'state.skuId',
          'state.category1',
          'state.channel',
          'state.shopId',
          'state.buyerId',
        ],
      },
      {
        id: 'time',
        label: '时间',
        fields: ['state.requestedAt', 'state.auditedAt', 'state.refundedAt'],
      },
    ],
    fields: [
      text('state.afterSaleNo', '售后单号', { cell: 'copyable' }),
      enumField('state.type', '售后类型', AFTER_SALE_TYPE_OPTIONS),
      enumField('state.reason', '售后理由', AFTER_SALE_REASON_OPTIONS),
      enumField('state.status', '处理状态', AFTER_SALE_STATUS_OPTIONS, {
        cell: 'status',
      }),
      {
        name: 'state.qty',
        label: '件数',
        kind: 'number',
        sortable: true,
        summary: ['SUM'],
      },
      amount('state.requestedAmount', '申请金额'),
      amount('state.refundedAmount', '实退金额'),
      text('state.orderNo', '订单号', { cell: 'copyable' }),
      text('state.lineId', '行号'),
      text('state.title', '商品'),
      text('state.skuId', 'SKU'),
      enumField('state.category1', '一级类目', CATEGORY_OPTIONS),
      enumField('state.channel', '渠道', CHANNEL_OPTIONS),
      enumField('state.shopId', '店铺', SHOP_OPTIONS),
      {
        name: 'state.buyerId',
        label: '买家',
        kind: 'reference',
        remote: MEMBER_OPTIONS,
        sortable: true,
      },
      time('state.requestedAt', '申请时间'),
      time('state.auditedAt', '审核时间'),
      time('state.refundedAt', '退款时间'),
    ],
    record: {
      rowKey: 'state.afterSaleNo',
      paging: 'paged',
      layouts: ['table', 'card'],
      maxWindow: 10_000,
    },
    analysis: {
      count: true,
      having: true,
      expressions: true,
      limits: { maxLimit: 1000 },
      fields: [
        ...terms(
          'state.type',
          'state.reason',
          'state.status',
          'state.title',
          'state.category1',
          'state.channel',
          'state.shopId',
        ),
        ...numeric('state.requestedAmount', 'state.refundedAmount'),
        { field: 'state.qty', groups: [], functions: [SUM] },
        ...dated('state.requestedAt', 'state.refundedAt'),
      ],
    },
    views,
  };
}

// ---------------------------------------------------------------- 会员

/**
 * 会员：会员等级、注册渠道、城市与城市等级；首单时间、累计单数、累计实付是读
 * 模型上由订单算出的字段。
 */
export function memberDefinition(
  id: string,
  views: SystemView[] = [],
): DataViewDefinition {
  return {
    id,
    title: '会员',
    recordNoun: '会员',
    kind: 'data',
    source: RETAIL_SOURCES.members,
    fields: [
      text('state.id', '会员号', { cell: 'copyable' }),
      text('state.nick', '昵称'),
      enumField('state.level', '会员等级', LEVEL_OPTIONS),
      enumField('state.registerChannel', '注册渠道', CHANNEL_OPTIONS),
      time('state.registeredAt', '注册时间'),
      text('state.province', '省份'),
      text('state.city', '城市'),
      enumField('state.cityTier', '城市等级', TIER_OPTIONS),
      time('state.firstOrderAt', '首单时间'),
      {
        // 已付款的主单数：一次结账算一次，拆成几张子单都算一次。
        name: 'state.orderCount',
        label: '购买次数',
        kind: 'number',
        sortable: true,
        summary: ['SUM', 'AVG', 'MAX'],
      },
      amount('state.totalPaid', '累计实付'),
    ],
    record: {
      rowKey: 'state.id',
      paging: 'paged',
      layouts: ['table', 'card'],
      maxWindow: 10_000,
    },
    analysis: {
      count: true,
      having: true,
      expressions: true,
      limits: { maxLimit: 1000 },
      fields: [
        ...terms(
          'state.level',
          'state.registerChannel',
          'state.province',
          'state.cityTier',
        ),
        {
          field: 'state.orderCount',
          groups: [TERMS, HISTOGRAM],
          functions: [SUM, AVG, MAX],
        },
        ...numeric('state.totalPaid'),
        ...dated('state.registeredAt', 'state.firstOrderAt'),
      ],
    },
    views,
  };
}

// ---------------------------------------------------------------- 运单

export const WAYBILL_STATUS_OPTIONS: FieldOption[] = [
  { value: 'IN_TRANSIT', label: '在途' },
  { value: 'SIGNED', label: '已签收', tone: 'success' },
  { value: 'REJECTED', label: '拒收', tone: 'danger' },
  { value: 'LOST', label: '丢件或退回', tone: 'danger' },
];

/** 运单宽表的 20 列：运营对账、查时效时横着看的全部字段。 */
export const WAYBILL_COLUMNS = [
  'state.waybillNo',
  'state.carrier',
  'state.status',
  'state.orderNo',
  'state.packageNo',
  'state.parentOrderNo',
  'state.warehouse',
  'state.province',
  'state.city',
  'state.cityTier',
  'state.remote',
  'state.lineCount',
  'state.itemQty',
  'state.weightKg',
  'state.shippedAt',
  'state.signedAt',
  'state.shipToSignHours',
  'state.buyerId',
  'firstEventTime',
  'eventTime',
];

/**
 * 运单（包裹读模型）：子单 `packages` 的展开，一包一行，和订单同源、不会对
 * 不上。承运商、仓库、目的地、重量、时效与签收都在一行里。
 */
export function waybillDefinition(
  id: string,
  views: SystemView[] = [],
): DataViewDefinition {
  return {
    id,
    title: '运单',
    recordNoun: '包裹',
    kind: 'data',
    source: RETAIL_SOURCES.waybills,
    fields: [
      text('state.waybillNo', '运单号', { cell: 'copyable' }),
      enumField('state.carrier', '承运商', CARRIER_OPTIONS),
      enumField('state.status', '物流状态', WAYBILL_STATUS_OPTIONS, {
        cell: 'status',
      }),
      text('state.orderNo', '订单号', { cell: 'copyable' }),
      text('state.packageNo', '包裹号'),
      text('state.parentOrderNo', '主单号'),
      enumField('state.warehouse', '发货仓', WAREHOUSE_OPTIONS),
      text('state.province', '省份'),
      text('state.city', '城市'),
      enumField('state.cityTier', '城市等级', TIER_OPTIONS),
      {
        name: 'state.remote',
        label: '偏远地区',
        kind: 'boolean',
        sortable: true,
      },
      {
        name: 'state.lineCount',
        label: '商品行',
        kind: 'number',
        sortable: true,
        summary: ['SUM'],
      },
      {
        name: 'state.itemQty',
        label: '件数',
        kind: 'number',
        sortable: true,
        summary: ['SUM'],
      },
      {
        name: 'state.weightKg',
        label: '重量',
        kind: 'number',
        sortable: true,
        numberFormat: KG,
        summary: ['SUM', 'AVG', 'MAX'],
      },
      time('state.shippedAt', '发货时间'),
      time('state.signedAt', '签收时间'),
      {
        name: 'state.shipToSignHours',
        label: '发货到签收',
        kind: 'number',
        sortable: true,
        numberFormat: HOURS,
        summary: ['AVG', 'MIN', 'MAX'],
      },
      {
        name: 'state.buyerId',
        label: '买家',
        kind: 'reference',
        remote: MEMBER_OPTIONS,
        sortable: true,
      },
      time('firstEventTime', '揽收入网'),
      time('eventTime', '最近更新'),
    ],
    record: {
      rowKey: 'state.waybillNo',
      paging: 'paged',
      layouts: ['table', 'card'],
      maxWindow: 10_000,
    },
    analysis: {
      count: true,
      having: true,
      expressions: true,
      limits: { maxLimit: 1000 },
      fields: [
        ...terms(
          'state.carrier',
          'state.status',
          'state.warehouse',
          'state.province',
          'state.cityTier',
          'state.remote',
        ),
        ...numeric('state.shipToSignHours', 'state.weightKg'),
        { field: 'state.itemQty', groups: [], functions: [SUM] },
        ...dated('state.shippedAt', 'state.signedAt'),
      ],
    },
    views,
  };
}

// ---------------------------------------------------------------- 订单事件流

const EVENT_PREFIX = 'me.ahoo.qimu.retail';

/** 事件类型：Wow 以事件类型命名事件体，名字按事件本身写。 */
export const ORDER_EVENT_TYPES: FieldOption[] = [
  ['OrderCreated', '下单'],
  ['OrderPaid', '付款成功', 'success'],
  ['OrderPaymentTimedOut', '支付超时', 'warning'],
  ['OrderCancelled', '订单取消', 'danger'],
  ['AddressChanged', '改收货地址'],
  ['PackageShipped', '包裹发出'],
  ['InvoiceIssued', '开具发票'],
  ['PackageSigned', '包裹签收', 'success'],
  ['PackageRejected', '包裹拒收', 'danger'],
  ['AfterSaleRequested', '申请售后', 'warning'],
  ['RefundSucceeded', '退款成功'],
  ['OrderClosed', '订单关闭'],
  ['OrderCompleted', '交易完成', 'success'],
].map(([type, label, tone]) => ({
  value: `${EVENT_PREFIX}.${type}`,
  label,
  ...(tone ? { tone: tone as FieldTone } : {}),
}));

/** 某一种事件的完整类型名。 */
export function eventType(type: string): string {
  return `${EVENT_PREFIX}.${type}`;
}

/**
 * 交易订单的事件流：一条记录是一次命令追加的事件（版本、时刻），事件在数组
 * `body` 里。按事件筛选是元素匹配，按事件分析展开 `body`、以事件为计数单位。
 * 只生成最近 90 天下单的子单（事件流分析台看的本来就是近期）。
 */
export function orderEventsDefinition(
  id: string,
  views: SystemView[] = [],
): DataViewDefinition {
  return {
    id,
    title: '订单事件流',
    recordNoun: '事件流',
    kind: 'data',
    source: RETAIL_SOURCES.orderEvents,
    fields: [
      text('aggregateId', '订单号', { cell: 'copyable' }),
      text('id', '事件流 ID', { cell: 'copyable' }),
      { name: 'version', label: '版本', kind: 'number', sortable: true },
      time('createTime', '事件时间'),
      {
        name: 'body',
        label: '事件',
        kind: 'elementMatch',
        operators: ['ELEMENT_MATCH'],
        elementTitle: 'bodyType',
        elements: [
          {
            name: 'bodyType',
            label: '事件类型',
            kind: 'enum',
            options: ORDER_EVENT_TYPES,
          },
          { name: 'name', label: '事件名', kind: 'string' },
        ],
      },
      text('commandId', '命令 ID', { cell: 'copyable' }),
      {
        name: 'ownerId',
        label: '买家会员号',
        kind: 'ownerId',
      },
    ],
    record: {
      rowKey: 'id',
      // 事件流一条接一条往后读：游标分页。
      paging: 'cursor',
      layouts: ['table', 'card'],
    },
    analysis: {
      count: true,
      having: true,
      expressions: true,
      limits: { maxLimit: 1000 },
      fields: [
        {
          field: 'aggregateId',
          groups: [TERMS],
          functions: [],
          distinctCount: true,
        },
        { field: 'version', groups: [TERMS], functions: [AVG, MAX] },
        {
          field: 'createTime',
          groups: [DATE_HISTOGRAM],
          functions: [MIN, MAX],
          dateUnits: [WEEK, DAY, HOUR],
        },
      ],
      elements: [{ path: 'body', aggregations: terms('bodyType', 'name') }],
    },
    views,
  };
}
