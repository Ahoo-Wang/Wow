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
  type RuntimeEnvironment,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
  type ViewStore,
} from '@ahoo-wang/fetcher-view-engine';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import { zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
import { rowSource } from './rowSource.js';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;

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
  recordNoun: '订单',
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
    // 第二个可度量的字段，公式才说得出一句话：「金额 − 成本」是毛利，
    // 「金额 − 金额」不是。它同样是钱，所以读法与金额一致。
    {
      name: 'cost',
      label: '成本',
      kind: 'number',
      sortable: true,
      summary: ['SUM'],
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
    // 一列时刻有最早与最晚，没有合计也没有平均——声明得出来的就只有这两个
    // 加计数，多声明一个也会被准入挡掉（`DATE_SUMMARY_FUNCTIONS`）。
    // 这批订单的时间存成 ISO 8601 文本，所以声明出来：不声明的时间字段按
    // Wow 快照的习惯当作纪元毫秒发送（`DEFAULT_TEMPORAL`）。
    {
      name: 'createdAt',
      label: '创建时间',
      kind: 'datetime',
      temporal: { type: 'date' },
      sortable: true,
      summary: ['MIN', 'MAX'],
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table', 'card'] },
  analysis: {
    count: true,
    // 写得出来的指标（D20 屏 B）与「只保留」：两者都是能力说了算，
    // 声明了托盘才长出「按公式」「按已有指标计算」与那一组比较行。
    expressions: true,
    having: true,
    fields: [
      { field: 'warehouse', groups: [TERMS], functions: [] },
      { field: 'status', groups: [TERMS], functions: [] },
      { field: 'amount', groups: [], functions: [SUM, AVG] },
      { field: 'cost', groups: [], functions: [SUM] },
    ],
  },
  views: [{ id: 'all', title: '全部订单', config: recordConfig() }],
};

/**
 * 同一份订单，外加一条声明出来的展开链：订单 → 明细项 → 批次（D20 屏 G）。
 *
 * 只有「展开」那个故事用它，别的故事照旧用上面那份——多一个数组字段就会
 * 多一个可筛的字段，而好几个故事正数着筛选面板里有几个。故事的数据源不会
 * 求值 `elements`（见 `rowSource.ts`），所以那个故事只走到托盘为止：链怎么
 * 走、计数单位跟着谁、收起带走哪几层，都是屏幕上看得见的。
 */
export const expandableOrdersDefinition: DataViewDefinition = {
  ...ordersDefinition,
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'items',
      label: '明细项',
      kind: 'array',
      elements: [
        { name: 'sku', label: '货号', kind: 'string' },
        { name: 'qty', label: '数量', kind: 'number' },
        {
          name: 'batches',
          label: '批次',
          kind: 'array',
          elements: [{ name: 'lot', label: '批号', kind: 'string' }],
        },
      ],
    },
  ],
  analysis: {
    ...ordersDefinition.analysis!,
    elements: [
      {
        path: 'items',
        aggregations: [
          { field: 'sku', groups: [TERMS], functions: [] },
          { field: 'qty', groups: [], functions: [SUM] },
        ],
      },
      {
        path: 'batches',
        aggregations: [{ field: 'lot', groups: [TERMS], functions: [] }],
      },
    ],
  },
};

/**
 * 同一份订单，外加两个装着对象的数组：明细（`lines`）与包裹（`parcels`）。
 *
 * 真实的 Wow 聚合里这是常态——事件流的 `body`、订单的明细、收货地址——而一列
 * 落在这种字段上从前写出整段 JSON。明细声明了 `elementTitle: 'sku'`，于是一格
 * 读成一枚一枚的货号；包裹没有声明，于是一格只说它装了几项。只有「按元素读」
 * 那个故事用它：多两个字段，筛选面板就多两项，好几个故事正数着那里有几个。
 */
export const orderLinesDefinition: DataViewDefinition = {
  ...ordersDefinition,
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'lines',
      label: '明细',
      kind: 'elementMatch',
      elementTitle: 'sku',
      elements: [
        { name: 'sku', label: '货号', kind: 'string' },
        { name: 'qty', label: '数量', kind: 'number' },
      ],
    },
    {
      name: 'parcels',
      label: '包裹',
      kind: 'elementMatch',
      elements: [
        { name: 'no', label: '包裹号', kind: 'string' },
        { name: 'weight', label: '重量', kind: 'number' },
      ],
    },
  ],
};

/**
 * 同一份订单，创建时间也能汇总：它的最早与最晚。只有「最晚」那个故事用它，
 * 别的故事照旧用上面那份——多一个能度量的字段，托盘「添加指标」的单子就多一项。
 */
export const datedOrdersDefinition: DataViewDefinition = {
  ...ordersDefinition,
  analysis: {
    ...ordersDefinition.analysis!,
    fields: [
      ...ordersDefinition.analysis!.fields,
      { field: 'createdAt', groups: [], functions: [MIN, MAX] },
    ],
  },
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
    lines: [
      { sku: 'TEA-01', qty: 2 },
      { sku: 'CUP-12', qty: 6 },
    ],
    parcels: [{ no: 'P-1001-1', weight: 1.2 }],
    warehouse: 'CN-EAST',
    status: 'PENDING',
    customer: 'c-03',
    tags: ['rush', 'fragile'],
    trackingUrl: 'https://example.com/track/SO-1001',
    note: '客户要求下午三点后送达。\n门卫代收需电话确认。',
    amount: 1280,
    cost: 900,
    createdAt: '2026-09-15T02:10:00.000Z',
  },
  {
    id: 'SO-1002',
    lines: [{ sku: 'VASE-03', qty: 1 }],
    parcels: [],
    warehouse: 'CN-EAST',
    // 已取消：一个"坏消息"的状态，好让语气三档在同一屏上齐。
    status: 'CANCELLED',
    customer: 'c-02',
    tags: [],
    note: '客户改约下周同一地址，原单作废。',
    amount: 640,
    cost: 500,
    createdAt: '2026-09-15T06:40:00.000Z',
  },
  {
    id: 'SO-1003',
    lines: [
      { sku: 'CARD-07', qty: 1 },
      { sku: 'TEA-01', qty: 1 },
      { sku: 'BOX-02', qty: 1 },
    ],
    parcels: [{ no: 'P-1003-1', weight: 0.4 }],
    warehouse: 'CN-NORTH',
    status: 'PENDING',
    customer: 'c-03',
    tags: ['gift'],
    trackingUrl: 'https://example.com/track/SO-1003',
    note: '随单附贺卡，不放价签。',
    amount: 2450,
    cost: 1500,
    createdAt: '2026-09-16T01:05:00.000Z',
  },
  {
    id: 'SO-1004',
    lines: [
      { sku: 'CUP-12', qty: 12 },
      { sku: 'PLATE-05', qty: 6 },
      { sku: 'BOWL-04', qty: 6 },
      { sku: 'SPOON-09', qty: 12 },
      { sku: 'TRAY-01', qty: 1 },
    ],
    parcels: [
      { no: 'P-1004-1', weight: 3.1 },
      { no: 'P-1004-2', weight: 2.8 },
    ],
    warehouse: 'CN-SOUTH',
    status: 'SHIPPED',
    customer: 'c-07',
    tags: ['rush'],
    trackingUrl: 'https://example.com/track/SO-1004',
    note: '已交承运商，预计次日达。',
    amount: 3120,
    cost: 2600,
    createdAt: '2026-09-16T05:30:00.000Z',
  },
  {
    id: 'SO-1005',
    lines: [
      { sku: 'GLASS-06', qty: 4 },
      { sku: 'GLASS-08', qty: 4 },
    ],
    parcels: [
      { no: 'P-1005-1', weight: 2.2 },
      { no: 'P-1005-2', weight: 2.0 },
      { no: 'P-1005-3', weight: 0.6 },
    ],
    warehouse: 'CN-SOUTH',
    status: 'PENDING',
    customer: 'c-02',
    tags: ['fragile', 'gift'],
    // 一条读不出的 URL：落回纯文本，绝不画成能点的链接。
    trackingUrl: 'javascript:alert(1)',
    note: '玻璃器皿，务必加气柱。\n仓库已备双层纸箱。\n第三行用来看截断。\n第四行看不见。',
    amount: 1760,
    cost: 1400,
    createdAt: '2026-09-17T02:20:00.000Z',
  },
  {
    id: 'SO-1006',
    lines: [{ sku: 'TEA-01', qty: 1 }],
    parcels: [{ no: 'P-1006-1', weight: 0.3 }],
    warehouse: 'CN-WEST',
    status: 'PENDING',
    customer: 'c-11',
    tags: ['vip'],
    trackingUrl: 'https://example.com/track/SO-1006',
    note: '',
    amount: 980,
    cost: 700,
    createdAt: '2026-09-17T08:45:00.000Z',
  },
  // A soft-deleted order. A Wow source answers only the records that are
  // not deleted unless the query says otherwise, and the story source does
  // the same (`rowSource.ts`), so this row is in no story until a view
  // asks for «deleted included» (D17-2).
  {
    id: 'SO-1007',
    lines: [{ sku: 'TEA-01', qty: 1 }],
    parcels: [],
    warehouse: 'CN-EAST',
    status: 'PENDING',
    customer: 'c-03',
    tags: [],
    note: '重复下单，已作废。',
    amount: 320,
    cost: 260,
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
        layout: { x: 0, y: 0, w: 14, h: 4 },
      },
      {
        id: 'by-warehouse',
        kind: 'view',
        title: '按仓库汇总',
        instanceId: 'orders-analysis',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 14, y: 0, w: 10, h: 4 },
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
        layout: { x: 0, y: 4, w: 8, h: 2 },
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

/**
 * `dashboardConfig()` as a store kept it before the grid had 24 columns: no
 * `columns`, no `tabs`, every `x` and `w` in twelfths. The engine reads it
 * into 24 columns on open (D22 E); the story over it measures that every
 * panel is drawn where these numbers put it.
 */
export function legacyDashboardConfig(): DashboardViewConfig {
  const current = dashboardConfig();
  const stored: Record<string, unknown> = {
    ...current,
    panels: current.panels.map(panel => ({
      ...panel,
      layout: {
        ...panel.layout,
        x: panel.layout.x / 2,
        w: panel.layout.w / 2,
      },
    })),
  };
  delete stored.columns;
  delete stored.tabs;
  return stored as unknown as DashboardViewConfig;
}

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
  'data' | 'empty' | 'slow' | 'failing' | 'no-aggregate' | 'outage';

/**
 * The switch behind the `outage` behaviour: while `down`, every query fails
 * as `failing` does, and it answers again once a play turns it back. It is
 * how a play shows a view that had data, lost its backend and got it back —
 * a refresh that fails over rows that are still good, and a retry. Module
 * state, like `aggregateCalls`, so a play reaches the source its story
 * built; a play that turns it on turns it off before it ends.
 */
export const outage = { down: false };

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
 * How many aggregations the sources below have been asked for since the page
 * loaded, for a play whose point is that a gesture asked for **none**.
 *
 * Switching a layout or a chart type redraws the rows that already came back
 * (`ANALYSIS_PRESENTATION_MEMBERS`, D20), and nothing on screen says whether
 * a query went out — the same numbers are there either way. A play therefore
 * reads this before the gesture and after it, never as an absolute: the
 * counter is the page's, and every story on it adds to the same number.
 */
export const aggregateCalls = { current: 0 };

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
    if (behaviour === 'failing' || (behaviour === 'outage' && outage.down))
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
    aggregate: query => {
      aggregateCalls.current += 1;
      return behaviour === 'no-aggregate'
        ? refuseAggregate()
        : answer(() => source.aggregate(query));
    },
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
  /**
   * The clock and zone the engine reads, for a story whose relative dates
   * — 「本月」 — must mean the same whenever it runs.
   */
  environment?: RuntimeEnvironment;
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
    ...(options.environment ? { environment: options.environment } : {}),
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
  recordNoun: '运单',
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
    // 两个时间都存成 ISO 8601 文本，条件照此发送。
    {
      name: 'shipDate',
      label: '发运日期',
      kind: 'date',
      temporal: { type: 'date' },
      sortable: true,
    },
    {
      name: 'createdAt',
      label: '创建时间',
      kind: 'datetime',
      temporal: { type: 'date' },
      sortable: true,
    },
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

/**
 * 同一份运单，也能分析：按目的城市、按创建的那一天或按运费区间分组，数单数、
 * 量运费合计。
 *
 * 三条分析回归靠它（2026-09-23 审查）：目的城市有十个，比色板的八色多两个，
 * 所以饼图得把尾巴并进「其他」而不是让两片同色；二十天按日倒序是一张「最近的
 * 在前」的表，而同一批行画成图，时间仍得从左往右走；运费按区间分组，每一行
 * 读成「¥0～500」这样的一段而不是它的下界。订单那份只有四个仓库、七单，这些
 * 都问不出来。
 */
export const waybillAnalysisDefinition: DataViewDefinition = {
  ...waybillsDefinition,
  analysis: {
    count: true,
    fields: [
      { field: 'destination', groups: [TERMS], functions: [] },
      {
        field: 'createdAt',
        groups: [DATE_HISTOGRAM],
        functions: [],
        dateUnits: [AggregationDateUnit.DAY],
      },
      // 运费从 ¥180 到 ¥1220，按 500 一档切成三段：一个桶的键只是那一段的
      // 下界，读成「¥0.00」说不出是哪一段（2026-09-23 真实后端走查）。
      { field: 'amount', groups: [HISTOGRAM], functions: [SUM] },
    ],
  },
};

/** 运单上的一个分析视图；问什么由故事给。 */
export function waybillAnalysisView(config: AnalysisViewConfig): ViewInstance {
  return {
    id: 'waybills-analysis',
    definitionId: waybillsDefinition.id,
    title: '运单分析',
    scope: 'shared',
    revision: '1',
    config,
  };
}

/** 50 行运单背后的数据源；五档行为与 `storySource` 的同义。 */
export function waybillSource(behaviour: SourceBehaviour = 'data'): ViewSource {
  return behavingSource(WAYBILLS, behaviour);
}

/* --------------------------------------------------------------------------
 * 失败的事件：处理器名有长有短，聚合 ID 是一串要一个字一个字抄走的码。
 *
 * 分析表的两条回归靠它（2026-09-23 审查 P1）：按处理器数失败次数，前两组
 * 降序是两个很长的名字、升序是两个很短的——自动布局下这一列跟着值变宽变窄，
 * 后面每一列都挪位，所以它量得出「列宽不随结果跳」；按聚合 ID 分组，量得出
 * ID 用的是与记录视图同一个等宽字。订单与运单的类目都是两三个字，量不出来。
 * ------------------------------------------------------------------------ */

const FAILED_EVENTS: RecordData[] = [
  ['OrderItemReservedTrackEventProcessor', 3],
  ['OrderItemReservedTrackEventProcessor', 5],
  ['OrderItemReservedTrackEventProcessor', 1],
  ['OrderItemReservedTrackEventProcessor', 2],
  ['InventorySnapshotProjectionHandler', 4],
  ['InventorySnapshotProjectionHandler', 1],
  ['InventorySnapshotProjectionHandler', 2],
  ['Mailer', 1],
  ['Audit', 2],
  ['Audit', 3],
].map(([processor, retries], index) => ({
  id: `evt-${index + 1}`,
  processor,
  // 两个事件落在同一个聚合上，好让按 ID 分组也有一组数到 2。
  aggregateId: `0b5f${String(Math.min(index, 8)).padStart(4, '0')}-7c1e-4d2a-9f3b-5e6a7b8c9d0e`,
  retries,
}));

/** 失败的事件：处理器、聚合 ID（可复制）与重试次数。 */
export const failedEventsDefinition: DataViewDefinition = {
  id: 'failed-events',
  title: '失败的事件',
  recordNoun: '失败事件',
  kind: 'data',
  source: 'failed-events',
  fields: [
    {
      name: 'id',
      label: '事件 ID',
      kind: 'string',
      cell: 'copyable',
      sortable: true,
    },
    { name: 'processor', label: '处理器', kind: 'string' },
    {
      name: 'aggregateId',
      label: '聚合 ID',
      kind: 'string',
      cell: 'copyable',
    },
    { name: 'retries', label: '重试次数', kind: 'number' },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'processor', groups: [TERMS], functions: [] },
      { field: 'aggregateId', groups: [TERMS], functions: [] },
      { field: 'retries', groups: [], functions: [SUM] },
    ],
  },
};

/** 失败的事件上的一个分析视图；问什么由故事给。 */
export function failedEventsView(config: AnalysisViewConfig): ViewInstance {
  return {
    id: 'failed-events-analysis',
    definitionId: failedEventsDefinition.id,
    title: '失败分析',
    scope: 'shared',
    revision: '1',
    config,
  };
}

/** 那十个失败事件背后的数据源；五档行为与 `storySource` 的同义。 */
export function failedEventsSource(
  behaviour: SourceBehaviour = 'data',
): ViewSource {
  return behavingSource(FAILED_EVENTS, behaviour);
}
