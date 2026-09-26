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
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  AggregationDatePart,
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  DateDiffUnit,
  FilterOperator,
  PagingMode,
  QueryValueKind,
  type FieldDescriptor,
  type QueryModelDescriptor,
  type QuerySemanticType,
} from '@ahoo-wang/wow-client';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE, analysisConfig, recordConfig } from './fixtures.js';
import { rowSource } from './rowSource.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

const { TERMS } = AggregationGroupType;
const { SUM } = AggregationFunction;

/**
 * Cross-border orders. The definition says nothing about how an amount
 * reads: no `numberFormat` anywhere. What each number *is* comes from the
 * service's descriptor (#3552) — the amount is money in the currency each
 * order holds, the shipping is money in yen, the weight a decimal of three
 * places — and the engine formats by that.
 */
const crossBorderOrders: DataViewDefinition = {
  id: 'cross-border-orders',
  title: '跨境订单',
  recordNoun: '订单',
  kind: 'data',
  source: 'cross-border-orders',
  fields: [
    { name: 'id', label: '订单号', kind: 'string', sortable: true },
    {
      name: 'market',
      label: '市场',
      kind: 'enum',
      options: [
        { value: 'JP', label: '日本' },
        { value: 'CN', label: '中国' },
        { value: 'US', label: '美国' },
      ],
    },
    { name: 'currency', label: '币种', kind: 'string' },
    { name: 'amount', label: '金额', kind: 'number', summary: ['SUM'] },
    { name: 'shipping', label: '运费', kind: 'number', summary: ['SUM'] },
    { name: 'weight', label: '重量（kg）', kind: 'number' },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      { field: 'market', groups: [TERMS], functions: [] },
      {
        field: 'currency',
        groups: [TERMS],
        functions: [],
        any: true,
        distinctCount: true,
      },
      { field: 'amount', groups: [], functions: [SUM] },
      { field: 'shipping', groups: [], functions: [SUM] },
    ],
  },
  views: [],
};

/**
 * Eight orders in three markets. Japan pays in yen and the US in dollars;
 * one Chinese order was paid in dollars, so the Chinese market holds two
 * currencies and has no one total.
 */
const ROWS: RecordData[] = [
  ['JP-1001', 'JP', 'JPY', 12800, 1200, 1.25],
  ['JP-1002', 'JP', 'JPY', 4500, 800, 0.4],
  ['CN-2001', 'CN', 'CNY', 1299.5, 0, 2.1],
  ['CN-2002', 'CN', 'CNY', 89.9, 600, 0.35],
  ['CN-2003', 'CN', 'USD', 45, 1500, 0.8],
  ['US-3001', 'US', 'USD', 129.99, 2400, 3.2],
  ['US-3002', 'US', 'USD', 19.5, 900, 0.15],
  ['US-3003', 'US', 'USD', 250, 3100, 5.75],
].map(([id, market, currency, amount, shipping, weight]) => ({
  id,
  market,
  currency,
  amount,
  shipping,
  weight,
}));

const SEMANTICS: Record<string, QuerySemanticType> = {
  amount: { type: 'MONEY', scale: 2, currencyField: 'currency' },
  shipping: { type: 'MONEY', scale: 0, currency: 'JPY' },
  weight: { type: 'DECIMAL', scale: 3 },
};

function described(path: string): FieldDescriptor {
  const semantic = SEMANTICS[path];
  return {
    path,
    types: [semantic ? 'NUMBER' : 'STRING'] as never,
    kind: QueryValueKind.SCALAR,
    nullable: true,
    project: true,
    ...(semantic ? { semantic } : {}),
    filter: { operators: Object.values(FilterOperator) },
    sort: { paged: true, cursor: true },
    aggregate: {
      groups: Object.values(AggregationGroupType),
      missingKey: true,
      functions: Object.values(AggregationFunction),
      distinctCount: true,
      percentile: true,
      any: true,
      firstLast: true,
      expressionInput: true,
      inMetricFilter: true,
    },
    aliases: [],
  };
}

/** What the orders service answers at `GET …/snapshot/schema`. */
const descriptor: QueryModelDescriptor = {
  model: 'SNAPSHOT',
  version: 'sha256:cross-border-orders-1',
  timeZone: 'Asia/Shanghai',
  record: {
    identity: 'id',
    paging: [PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR],
    rootOperators: [FilterOperator.ID, FilterOperator.IDS],
  },
  limits: {
    maxListSize: 1000,
    defaultListSize: 10,
    maxPageSize: 100,
    maxPageWindow: 10_000,
    maxFilterNodes: 256,
    maxFilterValues: 1000,
    maxSortFields: 8,
    aggregation: {
      maxGroups: 8,
      maxMetrics: 16,
      maxElements: 4,
      maxLimit: 1000,
      maxExpressionDepth: 8,
      maxExpressionNodes: 64,
    },
  },
  analysis: {
    metrics: Object.values(AggregationMetricType),
    expressions: true,
    having: { metrics: Object.values(AggregationMetricType) },
    sort: { groups: true, metrics: true },
    dense: true,
    approximate: [],
    dateUnits: Object.values(AggregationDateUnit),
    dateParts: Object.values(AggregationDatePart),
    dateDiffUnits: Object.values(DateDiffUnit),
  },
  fields: crossBorderOrders.fields.map(field => described(field.name)),
  elements: [],
  dynamic: [],
  constraints: [],
};

function moneySource(): ViewSource {
  return {
    ...rowSource(ROWS),
    describe: async () => ({
      notModified: false,
      descriptor,
      version: descriptor.version,
    }),
  };
}

const orders: ViewInstance = {
  id: 'cross-border-all',
  definitionId: crossBorderOrders.id,
  title: '全部跨境订单',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    summaries: [
      { field: 'amount', fn: 'SUM' },
      { field: 'shipping', fn: 'SUM' },
    ],
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'market' },
        { field: 'amount' },
        { field: 'shipping' },
        { field: 'weight' },
      ],
    },
    card: { title: 'id', fields: ['market', 'amount'] },
  }),
};

const byMarket: ViewInstance = {
  id: 'cross-border-by-market',
  definitionId: crossBorderOrders.id,
  title: '各市场金额',
  scope: 'personal',
  revision: '1',
  config: analysisConfig({
    groups: [{ alias: 'market', field: 'market', type: 'TERMS' }],
    metrics: [
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
      {
        alias: 'shipping',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'shipping' },
      },
    ],
    layout: 'table',
    table: { columns: [], totals: true },
    chart: {
      type: 'bar',
      cartesian: { x: 'market', series: [{ metric: 'amount' }] },
    },
  }),
};

type Scene = 'records' | 'analysis';

function MoneyDemo({ scene }: { scene: Scene }) {
  return (
    <StoryEngine
      key={scene}
      create={() =>
        new ViewEngine({
          definitions: [crossBorderOrders],
          store: new MemoryViewStore({ instances: [orders, byMarket] }),
          resolveSource: moneySource,
          limits: DEFAULT_RUNTIME_LIMITS,
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={crossBorderOrders.id}
          instanceId={scene === 'records' ? orders.id : byMarket.id}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const FIXTURE =
  '内存 ViewStore · 跨境订单 8 条（日元、人民币、美元）· 能力描述给出金额、运费与重量的语义';

const description = `**能力 · 金额与小数**

定义里没有写任何数字格式。服务端的能力描述（#3552）说：金额是**按每条记录自己的币种**记的钱（\`MONEY\`，\`currencyField: currency\`，两位小数），运费是**日元**（\`MONEY\`，\`currency: JPY\`，没有小数），重量是**三位小数**（\`DECIMAL\`）。引擎照这些格式化：单元格、详情、汇总、分析表、图表坐标与提示、导出都一样；定义自己写了 \`numberFormat\` 的字段仍按定义的来。

- **数据源**：${FIXTURE}。
- **记录**：每一行的金额按这一行的币种写（JP¥、¥、US$）；运费一律日元、不带小数；重量三位小数。本页金额的汇总混了三种货币，写「多种货币」，不给一个没有意义的和。
- **分析**：按市场分组，日本、美国各是一种货币，照写；中国市场有一单是美元，这一组的金额写「多种货币」；合计同样。结果上方的提示给出「按「币种」分组」，按一下就按币种分开，每一行只剩一种货币。
- **导出**：金额写成数本身，旁边多一列「金额（币种）」；运费旁边的币种一列都是 JPY。`;

const meta = {
  title: 'View Engine/能力/金额与小数',
  component: MoneyDemo,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  args: { scene: 'records' },
  argTypes: { scene: { table: { disable: true } } },
} satisfies Meta<typeof MoneyDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Each order's amount in its own currency; a page total across currencies is not one. */
export const Records: Story = {
  name: '记录：各按各的币种',
  args: { scene: 'records' },
};

/** Totals by market: a market holding two currencies has none, and says so. */
export const ByMarket: Story = {
  name: '分析：多种货币不合计',
  args: { scene: 'analysis' },
};
