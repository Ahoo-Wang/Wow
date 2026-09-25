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
  ViewInstance,
  ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  savedViews,
  storySource,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The orders with a search over their notes, matched as a phrase — the way
 * the compensation console searches its error messages (G15). The
 * definition is code: it cannot know which store it will be deployed on.
 */
const searchableOrders: DataViewDefinition = {
  ...ordersDefinition,
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'q',
      label: '搜索备注',
      kind: 'search',
      searchFields: ['note'],
      searchMode: 'PHRASE',
    },
  ],
};

/** One field that admits every operator, both sorts and every aggregation. */
function described(path: string): FieldDescriptor {
  return {
    path,
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
 * On Elasticsearch the notes are searched as a phrase. On MongoDB without a
 * text index there is no full-text search at all, and the notes — a long
 * text the store keeps unindexed — take no condition either.
 */
function ordersDescriptor(store: Store, counts = false): QueryModelDescriptor {
  const fields = ordersDefinition.fields.map(field =>
    store === 'mongodb' && field.name === 'note'
      ? { ...described('note'), filter: { operators: [] } }
      : described(field.name),
  );
  return {
    model: 'SNAPSHOT',
    version: `sha256:orders-${store}`,
    timeZone: 'Asia/Shanghai',
    record: {
      identity: 'id',
      paging: [PagingMode.LIST, PagingMode.PAGED, PagingMode.CURSOR],
      rootOperators: [FilterOperator.ID, FilterOperator.IDS],
      ...(store === 'elasticsearch'
        ? {
            search: {
              modes: [SearchMode.TERMS, SearchMode.PHRASE],
              fields: ['note'],
            },
          }
        : {}),
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
    },
    fields,
    elements: [],
    dynamic: [],
    // A store that counts only what a condition narrows (Q3).
    constraints: counts ? [{ type: 'COUNT_REQUIRES_FILTER' }] : [],
  };
}

/** The story's orders, answering `describe` as the store in question would. */
function describedSource(store: Store, counts = false): ViewSource {
  const descriptor = ordersDescriptor(store, counts);
  return {
    ...storySource(),
    describe: async () => ({
      notModified: false,
      descriptor,
      version: descriptor.version,
    }),
  };
}

/**
 * A view saved on Elasticsearch — the orders whose notes mention 「加急」,
 * largest first — as it opens on MongoDB, where the notes take no
 * condition (Q2).
 */
const rushNotes: ViewInstance = {
  id: 'orders-rush-notes',
  definitionId: 'orders',
  title: '备注里有「加急」',
  scope: 'personal',
  revision: '1',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        { field: 'note', operator: 'CONTAINS', value: '加急' },
        { field: 'warehouse', operator: 'IN', value: ['CN-EAST'] },
      ],
    },
    sort: [{ field: 'amount', direction: 'DESC' }],
  }),
};

/** Every order, with no condition: what a store that counts nothing refuses (Q3). */
const everyOrder: ViewInstance = {
  id: 'orders-every',
  definitionId: 'orders',
  title: '全部订单',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

type Scene = Store | 'saved-on-mongodb' | 'needs-a-condition';

function NarrowingDemo({ scene }: { scene: Scene }) {
  const store: Store = scene === 'elasticsearch' ? 'elasticsearch' : 'mongodb';
  const opened =
    scene === 'saved-on-mongodb'
      ? rushNotes.id
      : scene === 'needs-a-condition'
        ? everyOrder.id
        : savedViews[0].id;
  return (
    <StoryEngine
      key={scene}
      create={() =>
        createStoryEngine({
          definitions: [searchableOrders, overviewDefinition],
          instances: [...savedViews, rushNotes, everyOrder],
          source: describedSource(store, scene === 'needs-a-condition'),
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={opened}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const FIXTURE =
  '内存 ViewStore · 同一份订单定义（带「搜索备注」）· 两份能力描述：Elasticsearch、MongoDB';

const description = `**能力 · 随部署收窄**

定义是代码，写的时候不知道部署在哪种存储上。数据源的 \`describe\` 答出服务端的能力描述（\`GET …/snapshot/schema\`），引擎在第一条查询之前读它，把定义收窄成「定义声明的 ∩ 描述列出的」，界面只提供服务端做得到的（capabilities.md，补偿控制台的 G15）。

- **数据源**：${FIXTURE}。
- **操作**：在两个故事之间切换，看工具栏与「添加条件」。
- **观察**：Elasticsearch 上有「搜索备注」检索框，按短语检索；MongoDB（没有文本索引）上检索框不出现，「备注」也不在可加条件的字段里——不是置灰，是不提供。视图照常打开，数据照常显示。
- **已保存的视图**（Q2）：在 Elasticsearch 上存下的「备注里有『加急』」到了 MongoDB 上，条件栏标出不可用的条件，视图不查询，状态行给出「移除不可用的条件」；按一下，条件与排序去掉，再按「应用」就查。
- **先加条件**（Q3）：服务端要求计数查询带条件时，不带条件的记录视图不查询，表格位置说「先添加一个条件」。`;

const meta = {
  title: 'View Engine/能力/随部署收窄',
  component: NarrowingDemo,
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
  args: { scene: 'elasticsearch' },
  argTypes: { scene: { table: { disable: true } } },
} satisfies Meta<typeof NarrowingDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Elasticsearch: the phrase search the definition declares is there. */
export const OnElasticsearch: Story = {
  name: 'Elasticsearch：有全文检索',
  args: { scene: 'elasticsearch' },
};

/** MongoDB without a text index: no search box, and no condition on the notes. */
export const OnMongoDb: Story = {
  name: 'MongoDB：没有全文检索',
  args: { scene: 'mongodb' },
};

/** A view saved where the notes could be filtered, opened where they cannot (Q2). */
export const SavedOnMongoDb: Story = {
  name: '已保存的视图用到了不可用的条件',
  args: { scene: 'saved-on-mongodb' },
};

/** A store that counts only what a condition narrows, and a view with none (Q3). */
export const NeedsACondition: Story = {
  name: '先加条件',
  args: { scene: 'needs-a-condition' },
};
