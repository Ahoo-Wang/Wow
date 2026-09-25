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
 * 栖木生活的数据定义，给第 4 批的仪表盘与嵌入页用（docs/scenarios.md 4.1）。
 *
 * 第 3 批（记录、分析、事件流的业务场景）同时在做它自己的四个定义
 * （`retail/definitions.ts`）。两批并行，所以仪表盘要的定义单放在这个文件里，
 * id 都带 `retail-` 前缀、不与那一批相撞；两批都合并之后，谁后到谁把这里换成
 * 那边的定义，这个文件就可以删掉。
 *
 * 字段都在快照的 `state.` 之下，时间是纪元毫秒（Wow 快照的习惯，也就是字段不
 * 声明 `temporal` 时引擎的缺省）。金额按元计，读成人民币。
 * ------------------------------------------------------------------------ */

import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type DashboardDefinition,
  type DataViewDefinition,
  type FieldDefinition,
  type FieldOption,
  type OptionSource,
  type RecordData,
  type RuntimeEnvironment,
  type ViewInstance,
  type ViewSource,
  type ViewStore,
} from '@ahoo-wang/wow-view-engine';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { rowSource } from '../rowSource.js';
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
  MEMBER_LEVELS,
  ORDER_TAGS,
  PAYMENT_METHODS,
  SHOPS,
  WAREHOUSES,
} from './catalog.js';
import { RETAIL_NOW, generateRetail, type RetailDataset } from './generate.js';

const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH, QUARTER } = AggregationDateUnit;

// ---------------------------------------------------------------- 数据与时钟

let dataset: RetailDataset | undefined;

/**
 * 默认种子、showcase 规模的数据集，第一次读取时生成一次（Chromium 里约 100 ms），
 * 之后同一页的所有故事共享。数据不可变；引擎与存储每次挂载都新建。
 */
export function retailData(): RetailDataset {
  dataset ??= generateRetail();
  return dataset;
}

/** 数据集里「现在」的那一刻：2026-09-22 10:00，上海时间。 */
export const RETAIL_ZONE = 'Asia/Shanghai';

/**
 * 引擎的时钟与时区钉在数据集的「现在」：「昨日」「本月」「近 30 天」在哪台机器、
 * 哪一天打开都一样，孪生才能断言数字。
 */
export const RETAIL_ENVIRONMENT: RuntimeEnvironment = defaultRuntimeEnvironment(
  { now: () => new Date(RETAIL_NOW), timeZone: RETAIL_ZONE },
);

// ---------------------------------------------------------------- 参考数据 → 选项

const options = (
  list: readonly { id: string; name: string }[],
): FieldOption[] => list.map(({ id, name }) => ({ value: id, label: name }));

const names = (list: readonly string[]): FieldOption[] =>
  list.map(name => ({ value: name, label: name }));

const CURRENCY = { style: 'currency', currency: 'CNY' } as const;

/** 一个人民币金额：合计与平均都读成钱。 */
function money(name: string, label: string): FieldDefinition {
  return {
    name,
    label,
    kind: 'number',
    sortable: true,
    summary: ['SUM', 'AVG'],
    numberFormat: CURRENCY,
  };
}

/** 一个时刻：纪元毫秒，可排序，汇总只有最早与最晚。 */
function moment(name: string, label: string): FieldDefinition {
  return {
    name,
    label,
    kind: 'datetime',
    sortable: true,
    summary: ['MIN', 'MAX'],
  };
}

export const CHANNEL_OPTIONS = options(CHANNELS);
export const SHOP_OPTIONS = options(SHOPS);
export const WAREHOUSE_OPTIONS = options(WAREHOUSES);
export const LEVEL_OPTIONS = options(MEMBER_LEVELS);
const TIER_OPTIONS: FieldOption[] = Object.entries(CITY_TIERS).map(
  ([value, label]) => ({ value, label }),
);
export const CATEGORY_OPTIONS = names(CATEGORIES.map(({ name }) => name));

/** 订单状态读成徽章，语气是业务的：已取消、已关闭是坏消息。 */
const STATUS_OPTIONS: FieldOption[] = [
  { value: 'PENDING_PAYMENT', label: '待付款', tone: 'warning' },
  { value: 'PAID', label: '待发货', tone: 'warning' },
  { value: 'PARTIALLY_SHIPPED', label: '部分发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'SIGNED', label: '已签收', tone: 'success' },
  { value: 'COMPLETED', label: '交易完成', tone: 'success' },
  { value: 'CANCELLED', label: '已取消', tone: 'danger' },
  { value: 'CLOSED', label: '已关闭', tone: 'danger' },
];

/** 活动：每一年的每一场各是一个值，名字带着年份。 */
const ACTIVITY_OPTIONS: FieldOption[] = ACTIVITIES.map(({ id, name }) => ({
  value: id,
  label: name,
}));

// ---------------------------------------------------------------- 子订单

/** `reference` 字段查买家候选的那个来源名。 */
export const RETAIL_MEMBERS_REMOTE = 'members';

export const RETAIL_ORDERS = 'retail-orders';

/**
 * 子订单（`trade_order` 聚合的快照）：履约与售后的单位。
 *
 * `state.items.title` 另外声明成一个根字段：搜索（`q`）只能点名根字段
 * （`definition.field.search-fields-unknown`），而客服按商品名找单时要搜的正
 * 是每一行的标题。Wow 的查询按 MongoDB 的路径读它——数组里任何一行的标题
 * 匹配，这张单就匹配——所以它是文档里真实存在的路径，不是造出来的字段。
 */
export const retailOrdersDefinition: DataViewDefinition = {
  id: RETAIL_ORDERS,
  title: '订单',
  recordNoun: '子订单',
  kind: 'data',
  source: RETAIL_ORDERS,
  fieldGroups: [
    {
      id: 'order',
      label: '订单',
      fields: [
        'state.orderNo',
        'state.parentOrderNo',
        'state.status',
        'state.shopId',
        'state.channel',
        'state.warehouse',
        'state.tags',
        'state.remark',
      ],
    },
    {
      id: 'buyer',
      label: '买家',
      fields: [
        'state.buyer.id',
        'state.buyer.nick',
        'state.buyer.level',
        'state.buyer.isNewBuyer',
      ],
    },
    {
      id: 'items',
      label: '商品',
      fields: ['state.items', 'state.items.title'],
    },
    {
      id: 'amounts',
      label: '金额',
      fields: [
        'state.amounts.listAmount',
        'state.amounts.payableAmount',
        'state.amounts.paidAmount',
        'state.amounts.refundedAmount',
        'state.amounts.freight',
        'state.payment.method',
        'state.promotion.activityId',
      ],
    },
    {
      id: 'address',
      label: '收货',
      fields: [
        'state.address.province',
        'state.address.city',
        'state.address.cityTier',
      ],
    },
    {
      id: 'timing',
      label: '时间与履约',
      fields: [
        'firstEventTime',
        'state.timing.paidAt',
        'state.timing.shipDueAt',
        'state.timing.shippedAt',
        'state.payToShipHours',
        'state.shipSlaBreached',
        'state.placedHour',
        'state.cancelReason',
      ],
    },
  ],
  fields: [
    {
      name: 'state.orderNo',
      label: '订单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    {
      name: 'state.parentOrderNo',
      label: '主单号',
      kind: 'string',
      cell: 'copyable',
    },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      cell: 'status',
      options: STATUS_OPTIONS,
    },
    {
      name: 'state.shopId',
      label: '店铺',
      kind: 'enum',
      options: SHOP_OPTIONS,
    },
    {
      name: 'state.channel',
      label: '渠道',
      kind: 'enum',
      options: CHANNEL_OPTIONS,
    },
    {
      name: 'state.warehouse',
      label: '仓库',
      kind: 'enum',
      options: WAREHOUSE_OPTIONS,
    },
    {
      name: 'state.tags',
      label: '标记',
      kind: 'array',
      cell: 'tags',
      options: options(ORDER_TAGS),
    },
    { name: 'state.remark', label: '买家留言', kind: 'string', cell: 'text' },
    {
      name: 'state.buyer.id',
      label: '买家',
      kind: 'reference',
      remote: RETAIL_MEMBERS_REMOTE,
    },
    { name: 'state.buyer.nick', label: '买家昵称', kind: 'string' },
    {
      name: 'state.buyer.level',
      label: '会员等级',
      kind: 'enum',
      options: LEVEL_OPTIONS,
    },
    { name: 'state.buyer.isNewBuyer', label: '新客', kind: 'boolean' },
    {
      name: 'state.items',
      label: '商品行',
      kind: 'elementMatch',
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
        { name: 'brand', label: '品牌', kind: 'enum', options: names(BRANDS) },
        { name: 'priceBand', label: '价格带', kind: 'string' },
        { name: 'qty', label: '件数', kind: 'number' },
        {
          name: 'salePrice',
          label: '成交单价',
          kind: 'number',
          numberFormat: CURRENCY,
        },
        {
          name: 'payAmount',
          label: '行实付',
          kind: 'number',
          numberFormat: CURRENCY,
        },
        {
          name: 'refundedAmount',
          label: '行已退',
          kind: 'number',
          numberFormat: CURRENCY,
        },
      ],
    },
    { name: 'state.items.title', label: '商品名', kind: 'string' },
    money('state.amounts.listAmount', '原价合计'),
    money('state.amounts.payableAmount', '应付金额'),
    money('state.amounts.paidAmount', '实付金额'),
    money('state.amounts.refundedAmount', '已退金额'),
    money('state.amounts.freight', '运费'),
    {
      name: 'state.payment.method',
      label: '支付方式',
      kind: 'enum',
      options: options(PAYMENT_METHODS),
    },
    {
      name: 'state.promotion.activityId',
      label: '活动',
      kind: 'enum',
      options: ACTIVITY_OPTIONS,
    },
    { name: 'state.address.province', label: '省份', kind: 'string' },
    { name: 'state.address.city', label: '城市', kind: 'string' },
    {
      name: 'state.address.cityTier',
      label: '城市等级',
      kind: 'enum',
      options: TIER_OPTIONS,
    },
    moment('firstEventTime', '下单时间'),
    moment('state.timing.paidAt', '付款时间'),
    moment('state.timing.shipDueAt', '发货期限'),
    moment('state.timing.shippedAt', '发货时间'),
    {
      name: 'state.payToShipHours',
      label: '付款到发货（小时）',
      kind: 'number',
      numberFormat: { maximumFractionDigits: 1 },
    },
    // 读模型派生（docs/scenarios.md 2.3）：Wow 聚合不能对两个时刻相减，也不能按
    // 「几点」分组，真实系统由投影在写读模型时算好。
    { name: 'state.shipSlaBreached', label: '发货超时', kind: 'boolean' },
    { name: 'state.placedHour', label: '下单时段', kind: 'number' },
    {
      name: 'state.cancelReason',
      label: '取消原因',
      kind: 'enum',
      options: options(CANCEL_REASONS),
    },
    {
      name: 'q',
      label: '搜索',
      kind: 'search',
      searchFields: ['state.orderNo', 'state.buyer.nick', 'state.items.title'],
    },
  ],
  record: {
    rowKey: 'state.orderNo',
    paging: 'paged',
    layouts: ['table', 'card'],
    // 宿主的「催发货」与「订单详情」读这两个字段，不管视图摆了哪些列。
    rowFields: ['state.warehouse', 'state.status'],
  },
  analysis: {
    count: true,
    expressions: true,
    having: true,
    fields: [
      ...[
        'state.status',
        'state.shopId',
        'state.channel',
        'state.warehouse',
        'state.buyer.level',
        'state.buyer.isNewBuyer',
        'state.payment.method',
        'state.promotion.activityId',
        'state.address.province',
        'state.address.city',
        'state.address.cityTier',
        'state.cancelReason',
        'state.shipSlaBreached',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.buyer.id',
        groups: [TERMS],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'state.buyer.nick',
        groups: [],
        functions: [],
        any: true,
      },
      {
        field: 'state.parentOrderNo',
        groups: [],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'firstEventTime',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY, WEEK, MONTH, QUARTER],
      },
      {
        field: 'state.timing.paidAt',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [DAY, WEEK, MONTH],
      },
      {
        field: 'state.timing.shipDueAt',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [DAY, WEEK, MONTH],
      },
      ...[
        'state.amounts.listAmount',
        'state.amounts.payableAmount',
        'state.amounts.paidAmount',
        'state.amounts.refundedAmount',
        'state.amounts.freight',
      ].map(field => ({ field, groups: [], functions: [SUM, AVG] })),
      {
        field: 'state.payToShipHours',
        groups: [HISTOGRAM],
        functions: [AVG, MIN, MAX],
        percentile: true,
      },
      { field: 'state.placedHour', groups: [TERMS, HISTOGRAM], functions: [] },
    ],
    elements: [
      {
        path: 'state.items',
        aggregations: [
          ...[
            'title',
            'skuId',
            'category1',
            'category2',
            'brand',
            'priceBand',
          ].map(field => ({ field, groups: [TERMS], functions: [] })),
          { field: 'salePrice', groups: [HISTOGRAM], functions: [AVG] },
          { field: 'qty', groups: [], functions: [SUM] },
          { field: 'payAmount', groups: [], functions: [SUM] },
          { field: 'refundedAmount', groups: [], functions: [SUM] },
        ],
      },
    ],
    limits: { maxLimit: 1000 },
  },
};

// ---------------------------------------------------------------- 售后单

export const RETAIL_AFTER_SALES = 'retail-after-sales';

const AFTER_SALE_STATUS: FieldOption[] = [
  { value: 'REQUESTED', label: '待审核', tone: 'warning' },
  { value: 'APPROVED', label: '已同意', tone: 'warning' },
  { value: 'REJECTED', label: '已拒绝', tone: 'danger' },
  { value: 'REFUNDED', label: '已退款', tone: 'success' },
  { value: 'EXCHANGED', label: '已换货', tone: 'success' },
];

/** 售后单：指向子单与行，带理由、申请与实退金额，以及三个时刻。 */
export const retailAfterSalesDefinition: DataViewDefinition = {
  id: RETAIL_AFTER_SALES,
  title: '售后单',
  recordNoun: '售后单',
  kind: 'data',
  source: RETAIL_AFTER_SALES,
  fields: [
    {
      name: 'state.afterSaleNo',
      label: '售后单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    {
      name: 'state.orderNo',
      label: '订单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    { name: 'state.title', label: '商品', kind: 'string' },
    {
      name: 'state.category1',
      label: '一级类目',
      kind: 'enum',
      options: CATEGORY_OPTIONS,
    },
    {
      name: 'state.channel',
      label: '渠道',
      kind: 'enum',
      options: CHANNEL_OPTIONS,
    },
    {
      name: 'state.shopId',
      label: '店铺',
      kind: 'enum',
      options: SHOP_OPTIONS,
    },
    {
      name: 'state.buyerId',
      label: '买家',
      kind: 'reference',
      remote: RETAIL_MEMBERS_REMOTE,
    },
    {
      name: 'state.type',
      label: '售后类型',
      kind: 'enum',
      options: options(AFTER_SALE_TYPES),
    },
    {
      name: 'state.reason',
      label: '售后理由',
      kind: 'enum',
      options: options(AFTER_SALE_REASONS),
    },
    {
      name: 'state.status',
      label: '状态',
      kind: 'enum',
      cell: 'status',
      options: AFTER_SALE_STATUS,
    },
    { name: 'state.qty', label: '件数', kind: 'number' },
    money('state.requestedAmount', '申请金额'),
    money('state.refundedAmount', '实退金额'),
    moment('state.requestedAt', '申请时间'),
    moment('state.refundedAt', '退款时间'),
  ],
  record: { rowKey: 'state.afterSaleNo', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    expressions: true,
    having: true,
    fields: [
      ...[
        'state.title',
        'state.category1',
        'state.channel',
        'state.shopId',
        'state.type',
        'state.reason',
        'state.status',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      ...['state.requestedAmount', 'state.refundedAmount'].map(field => ({
        field,
        groups: [],
        functions: [SUM, AVG],
      })),
      { field: 'state.qty', groups: [], functions: [SUM] },
      ...['state.requestedAt', 'state.refundedAt'].map(field => ({
        field,
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [DAY, WEEK, MONTH],
      })),
    ],
  },
};

// ---------------------------------------------------------------- 会员

export const RETAIL_MEMBERS = 'retail-members';

/** 会员：等级、注册渠道、城市，以及由订单算出的首单时间、单数与累计实付。 */
export const retailMembersDefinition: DataViewDefinition = {
  id: RETAIL_MEMBERS,
  title: '会员',
  recordNoun: '会员',
  kind: 'data',
  source: RETAIL_MEMBERS,
  fields: [
    {
      name: 'state.id',
      label: '会员号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    { name: 'state.nick', label: '昵称', kind: 'string' },
    {
      name: 'state.level',
      label: '会员等级',
      kind: 'enum',
      options: LEVEL_OPTIONS,
    },
    {
      name: 'state.registerChannel',
      label: '注册渠道',
      kind: 'enum',
      options: CHANNEL_OPTIONS,
    },
    { name: 'state.province', label: '省份', kind: 'string' },
    {
      name: 'state.cityTier',
      label: '城市等级',
      kind: 'enum',
      options: TIER_OPTIONS,
    },
    moment('state.registeredAt', '注册时间'),
    moment('state.firstOrderAt', '首单时间'),
    { name: 'state.orderCount', label: '已付款主单数', kind: 'number' },
    money('state.totalPaid', '累计实付'),
  ],
  record: { rowKey: 'state.id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    expressions: true,
    having: true,
    fields: [
      ...[
        'state.level',
        'state.registerChannel',
        'state.province',
        'state.cityTier',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.firstOrderAt',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [MONTH, QUARTER],
      },
      {
        field: 'state.orderCount',
        groups: [HISTOGRAM],
        functions: [SUM, AVG, MAX],
      },
      { field: 'state.totalPaid', groups: [], functions: [SUM, AVG] },
    ],
  },
};

// ---------------------------------------------------------------- 运单

export const RETAIL_WAYBILLS = 'retail-waybills';

/** 包裹（运单读模型）：由子单的 `packages` 展开，一包一行。 */
export const retailWaybillsDefinition: DataViewDefinition = {
  id: RETAIL_WAYBILLS,
  title: '运单',
  recordNoun: '包裹',
  kind: 'data',
  source: RETAIL_WAYBILLS,
  fields: [
    {
      name: 'state.waybillNo',
      label: '运单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    {
      name: 'state.orderNo',
      label: '订单号',
      kind: 'string',
      cell: 'copyable',
    },
    {
      name: 'state.carrier',
      label: '承运商',
      kind: 'enum',
      options: options(CARRIERS),
    },
    {
      name: 'state.warehouse',
      label: '仓库',
      kind: 'enum',
      options: WAREHOUSE_OPTIONS,
    },
    { name: 'state.province', label: '省份', kind: 'string' },
    moment('state.shippedAt', '发货时间'),
    moment('state.signedAt', '签收时间'),
    {
      name: 'state.shipToSignHours',
      label: '发货到签收（小时）',
      kind: 'number',
      numberFormat: { maximumFractionDigits: 1 },
    },
  ],
  record: { rowKey: 'state.waybillNo', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      ...['state.carrier', 'state.warehouse', 'state.province'].map(field => ({
        field,
        groups: [TERMS],
        functions: [],
      })),
      {
        field: 'state.shippedAt',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [DAY, WEEK, MONTH],
      },
      {
        field: 'state.shipToSignHours',
        groups: [HISTOGRAM],
        functions: [AVG, MIN, MAX],
        percentile: true,
      },
    ],
  },
};

// ---------------------------------------------------------------- 订单事件流

export const RETAIL_ORDER_EVENTS = 'retail-order-events';

/** 事件名的中文读法，按生命周期的先后。 */
const EVENT_NAMES: FieldOption[] = [
  { value: 'order_created', label: '下单' },
  { value: 'order_paid', label: '付款' },
  { value: 'order_payment_timed_out', label: '付款超时' },
  { value: 'address_changed', label: '改地址' },
  { value: 'invoice_issued', label: '开票' },
  { value: 'package_shipped', label: '包裹发出' },
  { value: 'package_signed', label: '包裹签收' },
  { value: 'package_rejected', label: '拒收' },
  { value: 'after_sale_requested', label: '申请售后' },
  { value: 'refund_succeeded', label: '退款成功' },
  { value: 'order_cancelled', label: '订单取消', tone: 'danger' },
  { value: 'order_closed', label: '订单关闭', tone: 'danger' },
  { value: 'order_completed', label: '交易完成', tone: 'success' },
];

/**
 * 子订单的事件流（`trade_order` 聚合，最近 90 天下单的单）：一条记录是一次命令
 * 追加的事件，事件在 `body` 里，按事件名读。
 */
export const retailOrderEventsDefinition: DataViewDefinition = {
  id: RETAIL_ORDER_EVENTS,
  title: '订单事件流',
  recordNoun: '事件流',
  kind: 'data',
  source: RETAIL_ORDER_EVENTS,
  fields: [
    {
      name: 'aggregateId',
      label: '订单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    { name: 'version', label: '版本', kind: 'number', sortable: true },
    moment('createTime', '事件时间'),
    {
      name: 'body',
      label: '事件',
      kind: 'elementMatch',
      operators: ['ELEMENT_MATCH'],
      elementTitle: 'name',
      elements: [
        { name: 'name', label: '事件', kind: 'enum', options: EVENT_NAMES },
        { name: 'revision', label: '事件修订', kind: 'string' },
      ],
    },
    {
      name: 'id',
      label: '事件流 ID',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      {
        field: 'createTime',
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY],
      },
    ],
    elements: [
      {
        path: 'body',
        aggregations: [{ field: 'name', groups: [TERMS], functions: [] }],
      },
    ],
  },
};

// ---------------------------------------------------------------- 仪表盘

export const RETAIL_BOARDS = 'retail-boards';

/** 仪表盘不拥有数据，定义只是它们在目录里的一项。 */
export const retailBoardsDefinition: DashboardDefinition = {
  id: RETAIL_BOARDS,
  title: '栖木生活 · 经营看板',
  kind: 'dashboard',
};

export const RETAIL_DEFINITIONS = [
  retailOrdersDefinition,
  retailAfterSalesDefinition,
  retailMembersDefinition,
  retailWaybillsDefinition,
  retailOrderEventsDefinition,
  retailBoardsDefinition,
];

// ---------------------------------------------------------------- 数据源

/**
 * 每个定义一个 `rowSource`：按引擎真正发出的查询作答，只在 mingo 前面加索引与
 * 缓存（docs/scenarios.md 2.8）。时间列按纪元毫秒排好，范围条件先二分切片。
 */
export function retailSources(
  data: RetailDataset = retailData(),
): Record<string, ViewSource> {
  const rows = (list: readonly object[]) => list as readonly RecordData[];
  return {
    [RETAIL_ORDERS]: rowSource(rows(data.orders), {
      timeField: 'firstEventTime',
    }),
    [RETAIL_AFTER_SALES]: rowSource(rows(data.afterSales), {
      timeField: 'firstEventTime',
    }),
    [RETAIL_MEMBERS]: rowSource(rows(data.members)),
    [RETAIL_WAYBILLS]: rowSource(rows(data.waybills), {
      timeField: 'firstEventTime',
    }),
    [RETAIL_ORDER_EVENTS]: rowSource(rows(data.events), {
      timeField: 'createTime',
    }),
  };
}

/** 买家候选：按昵称或会员号搜，按会员号取回名字（筛选值里只存快照）。 */
export function memberOptions(
  data: RetailDataset = retailData(),
): OptionSource {
  const all = data.members.map(({ state }) => ({
    value: state.id,
    label: `${state.nick}（${state.id}）`,
  }));
  const byId = new Map(all.map(option => [option.value, option]));
  return {
    search: async ({ query }) => {
      const text = query.trim();
      const found = text
        ? all.filter(option => option.label.includes(text))
        : all;
      return { items: found.slice(0, 20), nextCursor: null };
    },
    resolve: async ids =>
      ids.flatMap(id => {
        const option = byId.get(String(id));
        return option ? [option] : [];
      }),
  };
}

export interface RetailEngineOptions {
  instances?: ViewInstance[];
  /** 换掉整张数据源表，比如「加载中」「没有数据」「一个面板出错」的变体。 */
  sources?: Record<string, ViewSource>;
  store?: ViewStore;
  environment?: RuntimeEnvironment;
}

/**
 * 一台引擎：零售的五个定义与仪表盘定义、这次挂载新建的存储，时钟钉在
 * 数据集的「现在」。
 */
export function createRetailEngine(
  options: RetailEngineOptions = {},
): ViewEngine {
  const sources = options.sources ?? retailSources();
  const members = memberOptions();
  return new ViewEngine({
    definitions: RETAIL_DEFINITIONS,
    // 与真实的 Wow 服务一样，一页最多 100 行。
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store:
      options.store ?? new MemoryViewStore({ instances: options.instances }),
    resolveSource: key => {
      const source = sources[key];
      if (!source) throw new Error(`No retail source ${key}.`);
      return source;
    },
    resolveOptions: () => members,
    environment: options.environment ?? RETAIL_ENVIRONMENT,
  });
}
