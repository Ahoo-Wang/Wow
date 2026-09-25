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
  SearchMode,
  type FieldDescriptor,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import type {
  DataViewDefinition,
  FieldDefinition,
  ViewInstance,
  ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  orderLinesDefinition,
  overviewDefinition,
  recordConfig,
  savedViews,
  storySource,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The orders' lines with a search of their own (N4): a `search` field among
 * the array's elements, naming the element field it looks in. Inside the
 * condition 「明细有一项满足」 it is one more thing the same line must
 * match; the definition is code and cannot know which store it runs on.
 */
const searchableLines: DataViewDefinition = {
  ...orderLinesDefinition,
  fields: orderLinesDefinition.fields.map((field): FieldDefinition =>
    field.name === 'lines'
      ? {
          ...field,
          elements: [
            ...(field.elements ?? []),
            {
              name: 'q',
              label: '搜索货号',
              kind: 'search',
              searchFields: ['sku'],
            },
          ],
        }
      : field,
  ),
};

/** One field that admits every operator, both sorts and every aggregation. */
function described(path: string, scope?: string): FieldDescriptor {
  return {
    path,
    ...(scope ? { scope } : {}),
    types: ['STRING'],
    kind: QueryValueKind.SCALAR,
    nullable: true,
    project: true,
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

type Store = 'elasticsearch' | 'mongodb';

/**
 * What the orders service answers at `GET …/snapshot/schema` on each store.
 * Elasticsearch searches the text of a nested line (`elements[].search`);
 * MongoDB's `$text` is collection-wide and cannot sit in `$elemMatch`, so it
 * lists no element search. Both filter the lines one by one.
 */
function linesDescriptor(store: Store): QueryModelDescriptor {
  const fields = searchableLines.fields.flatMap(field => [
    described(field.name),
    ...(field.elements ?? [])
      .filter(element => element.kind !== 'search')
      .map(element => described(`${field.name}.${element.name}`, field.name)),
  ]);
  return {
    model: 'SNAPSHOT',
    version: `sha256:order-lines-${store}`,
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
    fields,
    elements: [
      {
        path: 'lines',
        filter: true,
        aggregate: true,
        ...(store === 'elasticsearch'
          ? {
              search: {
                modes: [SearchMode.TERMS, SearchMode.PHRASE],
                fields: ['lines.sku'],
              },
            }
          : {}),
      },
      { path: 'parcels', filter: true, aggregate: true },
    ],
    dynamic: [],
    constraints: [],
  };
}

/** The story's orders, answering `describe` as the store in question would. */
function describedSource(store: Store): ViewSource {
  const descriptor = linesDescriptor(store);
  return {
    ...storySource(),
    describe: async () => ({
      notModified: false,
      descriptor,
      version: descriptor.version,
    }),
  };
}

/** The orders with a line whose SKU mentions 「tea」, saved on Elasticsearch. */
const teaLines: ViewInstance = {
  id: 'orders-tea-lines',
  definitionId: 'orders',
  title: '明细里有茶',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    summaries: [],
    filter: {
      op: 'and',
      children: [
        {
          field: 'lines',
          operator: 'ELEMENT_MATCH',
          value: {
            op: 'and',
            children: [{ field: 'lines.q', operator: 'SEARCH', value: 'tea' }],
          },
        },
      ],
    },
    table: {
      columns: [
        { field: 'id', pinned: true },
        { field: 'status' },
        { field: 'lines' },
      ],
    },
  }),
};

function ElementSearchDemo({ store }: { store: Store }) {
  return (
    <StoryEngine
      key={store}
      create={() =>
        createStoryEngine({
          definitions: [searchableLines, overviewDefinition],
          instances: [...savedViews, teaLines],
          source: describedSource(store),
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={
            store === 'elasticsearch' ? teaLines.id : savedViews[0].id
          }
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const FIXTURE =
  '内存 ViewStore · 订单定义的明细（`lines`）里声明「搜索货号」· 两份能力描述：Elasticsearch、MongoDB';

const description = `**能力 · 在元素里检索（N4）**

一个数组的元素里声明一个检索字段（\`kind: 'search'\`，\`searchFields\` 写元素自己的字段），它就出现在「明细有一项满足」的条件里：搜的是同一项明细，查询是 \`ELEMENT_MATCH\` 里一条点名元素字段的 \`SEARCH\`。只有服务端的能力描述在这个元素上列了 \`search\` 才提供——Elasticsearch 能在嵌套作用域里检索，MongoDB 的文本索引是整个集合的，不能放进 \`$elemMatch\`。

- **数据源**：${FIXTURE}。
- **操作**：打开筛选，看「明细」条件里的「添加」。
- **观察**：Elasticsearch 上已保存的「明细里有茶」按货号检索出含 TEA-01 的订单，条件里是「搜索货号 tea」；MongoDB 上「明细」的条件里不列「搜索货号」——不是置灰，是不提供，货号、数量照旧可选。`;

const meta = {
  title: 'View Engine/能力/元素内检索',
  component: ElementSearchDemo,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="narrowing" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  args: { store: 'elasticsearch' },
  argTypes: { store: { table: { disable: true } } },
} satisfies Meta<typeof ElementSearchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Elasticsearch: the lines are searched inside the element match. */
export const OnElasticsearch: Story = {
  name: 'Elasticsearch：明细里能检索',
  args: { store: 'elasticsearch' },
};

/** MongoDB: the element match offers no search, only the line's fields. */
export const OnMongoDb: Story = {
  name: 'MongoDB：明细里不能检索',
  args: { store: 'mongodb' },
};
