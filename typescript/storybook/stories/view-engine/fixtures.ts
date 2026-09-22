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

import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  emptyDashboardConfig,
  type AnalysisViewConfig,
  type DashboardViewConfig,
  type DataViewDefinition,
  type DashboardDefinition,
  type FieldOption,
  type OptionSource,
  type RecordData,
  type RecordViewConfig,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
  type ViewStore,
} from '@ahoo-wang/fetcher-view-engine';
import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import { rowSource } from './rowSource.js';

// Wow's names for what the analysis side may group and compute by.
const { TERMS } = AggregationGroupType;
const { SUM, AVG } = AggregationFunction;

/**
 * The language every View Engine story is in.
 *
 * The fixtures below are Chinese — 订单号, 待出库, 华东仓 — so a workbench
 * left on the English catalogue puts two languages on one screen, which is a
 * screen nobody's application looks like. The shipped `zhCN` catalogue is
 * handed over the way a host hands it over, and `locale` goes with it so the
 * dates and the currency are read in the same language as the words around
 * them. `DataWorkbench`'s `English` story is the one that opts out, and it
 * is what keeps the English catalogue covered.
 */
export const HOST_LANGUAGE = { messages: zhCN, locale: 'zh-CN' } as const;

/**
 * One warehouse dataset, shared by every View Engine story.
 *
 * The data is shared because it is immutable; the store and the engine are
 * not, so each story builds its own — a scenario must never inherit another's
 * saved views.
 */
export const ordersDefinition: DataViewDefinition = {
  id: 'orders',
  title: '订单',
  kind: 'data',
  source: 'orders',
  fields: [
    // 订单号是拿去粘到别处的东西——发给客户、贴进工单、在另一个系统里查，
    // 所以这一列声明 `cell: 'copyable'`：读法与从前一模一样，旁边多一颗
    // 悬停才现身、键盘永远够得到的复制按钮（用户 2026-09-22 提出）。
    {
      name: 'id',
      label: '订单号',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    {
      name: 'warehouse',
      label: '仓库',
      kind: 'enum',
      options: [
        { value: 'CN-EAST', label: '华东' },
        { value: 'CN-NORTH', label: '华北' },
        { value: 'CN-SOUTH', label: '华南' },
        { value: 'CN-WEST', label: '西南' },
      ],
    },
    // 状态读成一枚徽章，颜色由定义说了算：哪一个状态是好消息属于业务，
    // 渲染层猜不得，而语气只能取主题已有的那几档。
    {
      name: 'status',
      label: '状态',
      kind: 'enum',
      cell: 'status',
      options: [
        { value: 'PENDING', label: '待出库', tone: 'warning' },
        { value: 'SHIPPED', label: '已发运', tone: 'success' },
        { value: 'CANCELLED', label: '已取消', tone: 'danger' },
      ],
    },
    /** 一个数组一枚一枚地画，拼成一枚会读成"名字里带逗号的一个标签"。 */
    {
      name: 'tags',
      label: '标记',
      kind: 'array',
      cell: 'tags',
      options: [
        { value: 'rush', label: '加急', tone: 'warning' },
        { value: 'gift', label: '礼品' },
        { value: 'fragile', label: '易碎', tone: 'danger' },
      ],
    },
    /** 外链走 `isSafeContentUrl`，与仪表盘的 markdown 链接同一条规则。 */
    /** 客户是另一份数据里的行：候选由宿主的 `OptionSource` 搜出来，值里带着名字（F-04）。 */
    { name: 'customer', label: '客户', kind: 'reference', remote: 'customers' },
    { name: 'trackingUrl', label: '运单', kind: 'string', cell: 'link' },
    /** 多行备注截到三行，整段留在 `title` 里。 */
    { name: 'note', label: '备注', kind: 'string', cell: 'text' },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG'],
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    // 一列时刻有最早与最晚，没有合计也没有平均——声明得出来的就只有这两个
    // 加计数，多声明一个也会被准入挡掉（`DATE_SUMMARY_FUNCTIONS`）。
    {
      name: 'createdAt',
      label: '创建时间',
      kind: 'datetime',
      sortable: true,
      summary: ['MIN', 'MAX'],
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  analysis: {
    count: true,
    fields: [
      { field: 'warehouse', groups: [TERMS], functions: [] },
      { field: 'status', groups: [TERMS], functions: [] },
      { field: 'amount', groups: [], functions: [SUM, AVG] },
    ],
  },
  views: [{ id: 'all', title: '全部订单', config: recordConfig() }],
};

/** Dashboards own no data; the definition is only their catalogue entry. */
export const overviewDefinition: DashboardDefinition = {
  id: 'overview',
  title: '概览',
  kind: 'dashboard',
};

export const ORDERS: RecordData[] = [
  {
    id: 'SO-1001',
    warehouse: 'CN-EAST',
    status: 'PENDING',
    customer: 'c-03',
    tags: ['rush', 'fragile'],
    trackingUrl: 'https://example.com/track/SO-1001',
    note: '客户要求下午三点后送达。\n门卫代收需电话确认。',
    amount: 1280,
    createdAt: '2026-09-15T02:10:00.000Z',
  },
  {
    id: 'SO-1002',
    warehouse: 'CN-EAST',
    // 已取消：一个"坏消息"的状态，好让语气三档在同一屏上齐。
    status: 'CANCELLED',
    customer: 'c-02',
    tags: [],
    note: '客户改约下周同一地址，原单作废。',
    amount: 640,
    createdAt: '2026-09-15T06:40:00.000Z',
  },
  {
    id: 'SO-1003',
    warehouse: 'CN-NORTH',
    status: 'PENDING',
    customer: 'c-03',
    tags: ['gift'],
    trackingUrl: 'https://example.com/track/SO-1003',
    note: '随单附贺卡，不放价签。',
    amount: 2450,
    createdAt: '2026-09-16T01:05:00.000Z',
  },
  {
    id: 'SO-1004',
    warehouse: 'CN-SOUTH',
    status: 'SHIPPED',
    customer: 'c-07',
    tags: ['rush'],
    trackingUrl: 'https://example.com/track/SO-1004',
    note: '已交承运商，预计次日达。',
    amount: 3120,
    createdAt: '2026-09-16T05:30:00.000Z',
  },
  {
    id: 'SO-1005',
    warehouse: 'CN-SOUTH',
    status: 'PENDING',
    customer: 'c-02',
    tags: ['fragile', 'gift'],
    // 一条读不出的 URL：落回纯文本，绝不画成能点的链接。
    trackingUrl: 'javascript:alert(1)',
    note: '玻璃器皿，务必加气柱。\n仓库已备双层纸箱。\n第三行用来看截断。\n第四行看不见。',
    amount: 1760,
    createdAt: '2026-09-17T02:20:00.000Z',
  },
  {
    id: 'SO-1006',
    warehouse: 'CN-WEST',
    status: 'PENDING',
    customer: 'c-11',
    tags: ['vip'],
    trackingUrl: 'https://example.com/track/SO-1006',
    note: '',
    amount: 980,
    createdAt: '2026-09-17T08:45:00.000Z',
  },
  // A soft-deleted order. A Wow source answers only the records that are
  // not deleted unless the query says otherwise, and the story source does
  // the same (`rowSource.ts`), so this row is in no story until a view
  // asks for «deleted included» (D17-2).
  {
    id: 'SO-1007',
    warehouse: 'CN-EAST',
    status: 'PENDING',
    customer: 'c-03',
    tags: [],
    note: '重复下单，已作废。',
    amount: 320,
    createdAt: '2026-09-17T09:10:00.000Z',
    deleted: true,
  },
];

export function recordConfig(
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [],
    pageSize: 20,
    layout: 'table',
    summaries: [{ field: 'amount', fn: 'SUM' }],
    table: {
      columns: [
        // The row key stays put while the middle scrolls, which is what the
        // pin is for; the host's action column does the same on the far side.
        { field: 'id', pinned: true },
        { field: 'warehouse' },
        { field: 'status' },
        { field: 'amount' },
      ],
    },
    card: { title: 'id', fields: ['warehouse', 'status', 'amount'] },
    ...overrides,
  };
}

export function analysisConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    groups: [{ alias: 'warehouse', field: 'warehouse', type: 'TERMS' }],
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
    ],
    sort: [],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'amount' }] },
    },
    ...overrides,
  };
}

/** A dashboard over the two saved views below, filtered by one global field. */
export function dashboardConfig(
  overrides: Partial<DashboardViewConfig> = {},
): DashboardViewConfig {
  return {
    ...emptyDashboardConfig(),
    fields: [
      {
        name: 'region',
        label: '仓库',
        kind: 'enum',
        options: [
          { value: 'CN-EAST', label: '华东' },
          { value: 'CN-NORTH', label: '华北' },
          { value: 'CN-SOUTH', label: '华南' },
          { value: 'CN-WEST', label: '西南' },
        ],
      },
    ],
    panels: [
      {
        id: 'pending',
        kind: 'view',
        title: '待出库明细',
        instanceId: 'orders-pending',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 0, w: 7, h: 4 },
      },
      {
        id: 'by-warehouse',
        kind: 'view',
        title: '按仓库汇总',
        instanceId: 'orders-analysis',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 7, y: 0, w: 5, h: 4 },
      },
      {
        id: 'runbook',
        kind: 'links',
        title: '值班手册',
        items: [
          {
            label: '出库异常处理',
            href: '/runbook/outbound',
            description: '先看这里',
          },
          { label: '联系仓储值班', href: 'mailto:ops@example.com' },
        ],
        layout: { x: 0, y: 4, w: 4, h: 2 },
      },
    ],
    ...overrides,
  };
}

export const savedViews: ViewInstance[] = [
  {
    id: 'orders-pending',
    definitionId: 'orders',
    title: '待出库订单',
    scope: 'shared',
    revision: '1',
    config: recordConfig({
      filter: {
        op: 'and',
        // An enum's operators take a list, which is what keeps a selection
        // when the operator flips between IN and NOT_IN.
        children: [{ field: 'status', operator: 'IN', value: ['PENDING'] }],
      },
      sort: [{ field: 'amount', direction: 'DESC' }],
    }),
  },
  {
    id: 'orders-analysis',
    definitionId: 'orders',
    title: '仓库金额分布',
    scope: 'shared',
    revision: '1',
    config: analysisConfig(),
  },
  // Appended, never inserted: stories address the two above by index. It is
  // here so a sidebar has both groups, and both kinds inside one of them.
  {
    id: 'orders-mine',
    definitionId: 'orders',
    title: '我盯的大额单',
    scope: 'personal',
    revision: '1',
    config: recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'amount', operator: 'GT', value: 5000 }],
      },
      sort: [{ field: 'createdAt', direction: 'DESC' }],
    }),
  },
];

/** A dashboard with nothing on it, which is a valid starting point. */
export function emptyDashboard(): DashboardViewConfig {
  return emptyDashboardConfig();
}

export const savedDashboard: ViewInstance = {
  id: 'overview-ops',
  definitionId: 'overview',
  title: '出库概览',
  scope: 'personal',
  revision: '1',
  config: dashboardConfig(),
};

/**
 * What a story wants the backend to do while it is on screen.
 *
 * `no-aggregate` is the half-failure: pages come back, aggregations do not.
 * A record view then keeps its rows and loses the scope of its summary row,
 * which is the one state where a number on screen would otherwise go on
 * meaning something other than what it says.
 */
export type SourceBehaviour =
  'data' | 'empty' | 'slow' | 'failing' | 'no-aggregate';

/**
 * The rows above behind a `ViewSource`, or a backend that refuses to answer.
 * It is the one knob the state stories turn: every state below the workbench
 * follows from what the backend does, and with data it answers the query the
 * engine sent — filtered, sorted, paged and aggregated — so what a story
 * shows is what those conditions select.
 */
/** Two dozen customers, as a reference field's source hands them over. */
export const CUSTOMERS: FieldOption[] = [
  '宏远贸易',
  '蓝海物流',
  '晨光食品',
  '恒信电子',
  '云帆科技',
  '嘉禾农业',
  '天工机械',
  '锦绣服饰',
  '远洋船务',
  '博雅文化',
  '瑞丰药业',
  '星辰能源',
  '华章印务',
  '绿野园林',
  '金桥地产',
  '四海餐饮',
  '联创软件',
  '鼎盛建材',
  '春华教育',
  '飞跃汽车',
  '百川水务',
  '明珠珠宝',
  '双鹤医疗',
  '长风航空',
].map((label, index) => ({
  value: `c-${String(index + 1).padStart(2, '0')}`,
  label,
}));

/**
 * The customers, searched: a page of eight at a time, matched on the name
 * or the id, and a little slow so the list's «searching» state can be seen.
 */
export function customerSource(delayMs = 300): OptionSource {
  const PAGE = 8;
  return {
    async search({ query, cursor }, signal) {
      await delay(delayMs);
      if (signal?.aborted) throw new Error('aborted');
      const needle = query.trim().toLowerCase();
      const matched = CUSTOMERS.filter(
        option =>
          needle === '' ||
          option.label.toLowerCase().includes(needle) ||
          String(option.value).includes(needle),
      );
      const start = Number(cursor ?? 0);
      const end = start + PAGE;
      return {
        items: matched.slice(start, end),
        nextCursor: end < matched.length ? String(end) : null,
      };
    },
    async resolve(ids) {
      return CUSTOMERS.filter(option => ids.includes(option.value));
    },
  };
}

export function storySource(behaviour: SourceBehaviour = 'data'): ViewSource {
  return behavingSource(ORDERS, behaviour);
}

/**
 * The same five behaviours over any set of rows, so a second dataset — the
 * wide one below — reaches every state this one does without a second copy
 * of the rules for what each state means.
 */
function behavingSource(
  rows: readonly RecordData[],
  behaviour: SourceBehaviour,
): ViewSource {
  const source = rowSource(behaviour === 'empty' ? [] : rows);
  const answer = async <T>(query: () => Promise<T>): Promise<T> => {
    if (behaviour === 'failing')
      throw new ViewStoreError('UNAVAILABLE', '仓储服务暂时不可用');
    // Long enough to look at, short enough that nobody waits for it.
    if (behaviour === 'slow') await delay(1_500);
    return query();
  };

  const refuseAggregate = async (): Promise<never> => {
    throw new ViewStoreError('UNAVAILABLE', '汇总服务暂时不可用');
  };

  return {
    paged: query => answer(() => source.paged(query)),
    cursor: query => answer(() => source.cursor(query)),
    aggregate: query =>
      behaviour === 'no-aggregate'
        ? refuseAggregate()
        : answer(() => source.aggregate(query)),
  };
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * The rows behind a source whose **export** behaves differently from the
 * view: slowly enough to watch the progress bar fill, or not at all.
 *
 * An export is the one query that asks for a whole `maxPageSize` page
 * (`runtime/exportRows.ts`), which is how this tells the two apart — a story
 * that counted requests instead would be undone by one refresh.
 */
export function storyExportSource(
  behaviour: 'slow' | 'failing',
  exportPageSize: number,
): ViewSource {
  const source = storySource('data');
  return {
    ...source,
    paged: async query => {
      if (query.pagination?.size !== exportPageSize) return source.paged(query);
      if (behaviour === 'failing')
        throw new ViewStoreError('UNAVAILABLE', '仓储服务暂时不可用');
      // Long enough to read the bar, short enough that nobody waits for it.
      await delay(1_200);
      return source.paged(query);
    },
  };
}

/**
 * The store the table-settings story writes into.
 *
 * Its regression play changes the columns, the pinning, a summary and the
 * sort, then saves — and what it has to prove is that the *saved config*
 * holds all four. The screen shows the draft whether or not anything landed,
 * so the store is the only witness, and a play cannot otherwise reach the
 * one its story built. It lives here rather than beside the story because
 * everything a stories module exports is taken for a story.
 */
export const tableSettingsStore: { current: MemoryViewStore | null } = {
  current: null,
};

export interface StoryEngineOptions {
  behaviour?: SourceBehaviour;
  instances?: ViewInstance[];
  definitions?: (DataViewDefinition | DashboardDefinition)[];
  /** A source of the story's own, where `behaviour` has no shape for it. */
  source?: ViewSource;
  /** Budgets the story is about, merged over the engine's defaults. */
  limits?: Partial<RuntimeLimits>;
  /**
   * The store to build on, when a story keeps a handle to it. A regression
   * play that asserts what a save *wrote* has to read the store itself: the
   * screen shows the draft either way, so asserting the screen would pass
   * just as happily with nothing persisted at all.
   *
   * Any `ViewStore`, not only the in-memory one: the write-outcome stories
   * hand over a store that answers the next write with a conflict, an
   * unknown result or a refusal (`outcomesStore.ts`).
   */
  store?: ViewStore;
}

/**
 * A fresh engine with a fresh store. Stories call it once per mount, because
 * saving, renaming and deleting are real writes and one scenario's leftovers
 * would be another's starting point.
 */
export function createStoryEngine(
  options: StoryEngineOptions = {},
): ViewEngine {
  return new ViewEngine({
    definitions: options.definitions ?? [ordersDefinition, overviewDefinition],
    store:
      options.store ??
      new MemoryViewStore({ instances: options.instances ?? savedViews }),
    resolveSource: () => options.source ?? storySource(options.behaviour),
    resolveOptions: () => customerSource(),
    ...(options.limits
      ? { limits: { ...DEFAULT_RUNTIME_LIMITS, ...options.limits } }
      : {}),
  });
}

/* --------------------------------------------------------------------------
 * 宽表：一个真的很宽的定义，外加 50 行。
 *
 * 订单那套只有八个字段，故事里最多摆五列，于是每一条与"宽"有关的规矩——表头
 * 粘在顶、两行汇总粘在底、左右两侧的冻结边、中间横滚、列宽拖动、导出窗口里的
 * 列清单、排序弹层里的好几个字段——都只在"没什么可滚"的桌面上验过。这份定义
 * 声明 21 个字段（保存的视图摆出其中 20 列），数据 50 行，正好是原始条目里那
 * 句「20 列 50 行」说的那张表。
 *
 * 数据是**算出来**的而不是抄出来的，但每一格只由行号决定，所以它和手写的常量
 * 一样确定：同一行永远是同一单，回归可以按值断言。
 * ------------------------------------------------------------------------ */

const WAYBILL_WAREHOUSES: FieldOption[] = [
  { value: 'CN-EAST', label: '华东仓' },
  { value: 'CN-NORTH', label: '华北仓' },
  { value: 'CN-SOUTH', label: '华南仓' },
  { value: 'CN-WEST', label: '西南仓' },
];

const WAYBILL_CARRIERS: FieldOption[] = [
  { value: 'SF', label: '顺丰' },
  { value: 'JD', label: '京东物流' },
  { value: 'YTO', label: '圆通' },
  { value: 'ZTO', label: '中通' },
];

const WAYBILL_CHANNELS: FieldOption[] = [
  { value: 'ROAD', label: '陆运' },
  { value: 'AIR', label: '空运' },
  { value: 'SEA', label: '海运' },
];

/** 四档状态，语气三档齐：好消息、坏消息，外加两档不带语气的过程态。 */
const WAYBILL_STATUSES: FieldOption[] = [
  { value: 'PENDING', label: '待揽收', tone: 'warning' },
  { value: 'IN_TRANSIT', label: '在途' },
  { value: 'DELIVERED', label: '已签收', tone: 'success' },
  { value: 'RETURNED', label: '已退回', tone: 'danger' },
];

const WAYBILL_PRIORITIES: FieldOption[] = [
  { value: 'P0', label: '加急' },
  { value: 'P1', label: '常规' },
  { value: 'P2', label: '次日达' },
];

const WAYBILL_TAGS: FieldOption[] = [
  { value: 'rush', label: '加急', tone: 'warning' },
  { value: 'cold', label: '冷链' },
  { value: 'fragile', label: '易碎', tone: 'danger' },
  { value: 'gift', label: '礼品' },
];

/**
 * 21 个字段，十种读法：主键、几段纯文本、四套枚举、一个数组、三个数字（格式
 * 与汇总函数各不相同）、两个布尔、一个日期、一个时刻、一个外链、一段多行备
 * 注。宽表要看的正是「读法各不相同的列横着排在一起」。
 */
export const waybillsDefinition: DataViewDefinition = {
  id: 'waybills',
  title: '运单',
  kind: 'data',
  source: 'waybills',
  fields: [
    { name: 'id', label: '运单号', kind: 'string', sortable: true },
    { name: 'orderNo', label: '订单号', kind: 'string', sortable: true },
    { name: 'customer', label: '客户', kind: 'string' },
    { name: 'receiver', label: '收件人', kind: 'string' },
    // 不在保存的列里：列设置的「还能加进来」那一区得有东西可加，导出窗口的
    // 列清单也才有得比——它只列屏幕上看得见的那几列。
    { name: 'phone', label: '联系电话', kind: 'string' },
    { name: 'destination', label: '目的城市', kind: 'string' },
    {
      name: 'warehouse',
      label: '发货仓',
      kind: 'enum',
      options: WAYBILL_WAREHOUSES,
    },
    {
      name: 'carrier',
      label: '承运商',
      kind: 'enum',
      options: WAYBILL_CARRIERS,
    },
    {
      name: 'channel',
      label: '运输方式',
      kind: 'enum',
      options: WAYBILL_CHANNELS,
    },
    {
      name: 'status',
      label: '状态',
      kind: 'enum',
      cell: 'status',
      options: WAYBILL_STATUSES,
    },
    {
      name: 'priority',
      label: '时效',
      kind: 'enum',
      options: WAYBILL_PRIORITIES,
    },
    {
      name: 'tags',
      label: '标记',
      kind: 'array',
      cell: 'tags',
      options: WAYBILL_TAGS,
    },
    {
      name: 'pieces',
      label: '件数',
      kind: 'number',
      sortable: true,
      summary: ['SUM'],
    },
    {
      name: 'weight',
      label: '重量',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG'],
      numberFormat: {
        style: 'unit',
        unit: 'kilogram',
        maximumFractionDigits: 1,
      },
    },
    {
      name: 'amount',
      label: '运费',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG'],
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    { name: 'insured', label: '已保价', kind: 'boolean' },
    { name: 'signed', label: '已签单', kind: 'boolean' },
    { name: 'shipDate', label: '发运日期', kind: 'date', sortable: true },
    { name: 'createdAt', label: '创建时间', kind: 'datetime', sortable: true },
    { name: 'trackingUrl', label: '跟踪链接', kind: 'string', cell: 'link' },
    { name: 'note', label: '备注', kind: 'string', cell: 'text' },
  ],
  // 21 个字段的目录：四组各管一摊，剩下七个谁也不归——单号、状态、标记、签
  // 单、链接与备注在列设置里排在最前、不戴标题，其后才是这四组。宽表是目录唯
  // 一真正有分量的地方：八个字段的定义不分组也找得到，二十个不行。
  fieldGroups: [
    {
      id: 'party',
      label: '收发双方',
      fields: ['customer', 'receiver', 'phone', 'destination'],
    },
    {
      id: 'transport',
      label: '运输',
      fields: ['warehouse', 'carrier', 'channel', 'priority'],
    },
    {
      id: 'billing',
      label: '计费',
      fields: ['pieces', 'weight', 'amount', 'insured'],
    },
    { id: 'timing', label: '时间', fields: ['shipDate', 'createdAt'] },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
};

/** 六段备注，含一条空的与一条要被截到三行的。 */
const WAYBILL_NOTES = [
  '',
  '收件人要求工作日上午送达。',
  '整箱冷链，途中不得转普货。\n温控记录随单回传。',
  '客户自提，到仓后电话通知。',
  '易碎品，外箱已加气柱。\n第二行说清楚谁签的字。\n第三行是最后看得见的一行。\n第四行看不见。',
  '与上一单合并配送，运费已分摊。',
];

const WAYBILL_CITIES = [
  '杭州',
  '上海',
  '南京',
  '北京',
  '天津',
  '广州',
  '深圳',
  '成都',
  '重庆',
  '西安',
];

const WAYBILL_CUSTOMERS = [
  '明远商贸',
  '恒通电子',
  '四海食品',
  '云图科技',
  '长风家居',
  '南山医药',
  '朝晖纺织',
  '临江重工',
];

const WAYBILL_RECEIVERS = [
  '张伟',
  '李娜',
  '王强',
  '刘洋',
  '陈静',
  '赵磊',
  '孙倩',
  '周涛',
  '吴敏',
  '郑凯',
];

/** 五套标记，含一套空的：数组单元格也要画得出「什么都没有」。 */
const WAYBILL_TAG_SETS = [
  [],
  ['rush'],
  ['fragile', 'cold'],
  ['gift'],
  ['rush', 'fragile'],
];

/** 状态不跟发货仓同周期，否则一列能从另一列猜出来。 */
const WAYBILL_STATUS_CYCLE = [0, 1, 2, 1, 3, 2, 0, 1, 2, 0];

const pad2 = (value: number) => String(value).padStart(2, '0');

/** 一行运单，只由行号决定。 */
function waybill(index: number): RecordData {
  const day = 1 + (index % 20);
  const status = WAYBILL_STATUSES[WAYBILL_STATUS_CYCLE[index % 10]].value;
  const id = `YD-${1001 + index}`;
  return {
    id,
    orderNo: `SO-${2001 + index}`,
    customer: WAYBILL_CUSTOMERS[index % WAYBILL_CUSTOMERS.length],
    receiver: WAYBILL_RECEIVERS[(index * 7) % WAYBILL_RECEIVERS.length],
    phone: `138${20250000 + index * 137}`,
    destination: WAYBILL_CITIES[(index * 3) % WAYBILL_CITIES.length],
    warehouse: WAYBILL_WAREHOUSES[index % 4].value,
    carrier: WAYBILL_CARRIERS[(index * 3 + 1) % 4].value,
    channel: WAYBILL_CHANNELS[index % 3].value,
    status,
    priority: WAYBILL_PRIORITIES[(index * 2) % 3].value,
    tags: WAYBILL_TAG_SETS[index % WAYBILL_TAG_SETS.length],
    pieces: 1 + (index % 7),
    weight: Number((2.5 + (index % 13) * 1.5).toFixed(1)),
    amount: 180 + (index % 17) * 65,
    insured: index % 3 === 0,
    signed: status === 'DELIVERED',
    shipDate: `2026-09-${pad2(day)}`,
    createdAt: `2026-09-${pad2(day)}T${pad2(6 + (index % 12))}:${pad2(
      (index * 7) % 60,
    )}:00.000Z`,
    trackingUrl: `https://example.com/track/${id}`,
    note: WAYBILL_NOTES[index % WAYBILL_NOTES.length],
  };
}

/** 50 行运单。 */
export const WAYBILLS: RecordData[] = Array.from({ length: 50 }, (_, index) =>
  waybill(index),
);

/**
 * 20 列、每页 50 条、三行汇总里的三个数字、三重排序。
 *
 * 左边只钉主键一列：宽表在 420px 的一栏里也要能看，左侧再多钉一列就把中间挤
 * 没了。右边那条边由宿主的操作列提供（D13），所以这条故事带着行动作开。
 */
export function waybillConfig(
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [
      { field: 'shipDate', direction: 'DESC' },
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ],
    pageSize: 50,
    layout: 'table',
    summaries: [
      { field: 'pieces', fn: 'SUM' },
      { field: 'weight', fn: 'SUM' },
      { field: 'amount', fn: 'SUM' },
    ],
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'orderNo' },
        { field: 'customer' },
        { field: 'receiver' },
        { field: 'destination' },
        { field: 'warehouse' },
        { field: 'carrier' },
        { field: 'channel' },
        { field: 'status' },
        { field: 'priority' },
        { field: 'tags' },
        { field: 'pieces' },
        { field: 'weight' },
        { field: 'amount' },
        { field: 'insured' },
        { field: 'signed' },
        { field: 'shipDate' },
        { field: 'createdAt' },
        { field: 'trackingUrl' },
        { field: 'note' },
      ],
    },
    card: {
      title: 'id',
      fields: [
        'customer',
        'destination',
        'warehouse',
        'carrier',
        'status',
        'pieces',
        'weight',
        'amount',
        'shipDate',
      ],
    },
    ...overrides,
  };
}

/** 保存在这份定义上的那个视图。 */
export const savedWaybillViews: ViewInstance[] = [
  {
    id: 'waybills-all',
    definitionId: 'waybills',
    title: '全部运单',
    scope: 'shared',
    revision: '1',
    config: waybillConfig(),
  },
];

/** 50 行运单背后的数据源；五档行为与 `storySource` 的同义。 */
export function waybillSource(behaviour: SourceBehaviour = 'data'): ViewSource {
  return behavingSource(WAYBILLS, behaviour);
}
