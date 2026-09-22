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
import type {
  DataViewDefinition,
  FilterTree,
  ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import {
  DashboardWorkbench,
  RecordWorkbench,
} from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  dashboardConfig,
  ordersDefinition,
  recordConfig,
  savedDashboard,
  savedViews,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The orders definition with an array field whose entries can be matched,
 * so the editor has every shape of condition to show: a plain one, a group,
 * and one that holds a tree.
 */
const withItems: DataViewDefinition = {
  ...ordersDefinition,
  // The picker lists fields under these, in this order.
  fieldGroups: [
    { id: 'basics', label: '基础信息', fields: ['warehouse', 'status'] },
    { id: 'money', label: '金额', fields: ['amount'] },
    { id: 'time', label: '时间', fields: ['createdAt'] },
  ],
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'items',
      label: '商品行',
      kind: 'elementMatch',
      elements: [
        { name: 'sku', label: 'SKU', kind: 'string' },
        { name: 'qty', label: '数量', kind: 'number' },
      ],
    },
    // The definition declares the soft-delete dimension (D17-2): until a
    // view says otherwise, the rows are the ones not deleted, and the bar
    // says so on its own.
    { name: '@deleted', label: '删除状态', kind: 'deletion' },
  ],
};

const RICH: FilterTree = {
  op: 'and',
  children: [
    { field: 'warehouse', operator: 'IN', value: ['CN-EAST'] },
    { field: 'status', operator: 'IN', value: ['PENDING', 'SHIPPED'] },
    { field: 'amount', operator: 'BETWEEN', value: [100, 5000] },
    {
      field: 'createdAt',
      operator: 'BETWEEN',
      value: { type: 'preset', preset: 'thisMonth' },
    },
    // A field chosen and nothing said yet: blank, not wrong.
    { field: 'id', operator: 'EQ', value: '' },
    {
      op: 'or',
      children: [
        { field: 'warehouse', operator: 'IN', value: ['CN-NORTH'] },
        { field: 'amount', operator: 'GT', value: 20000 },
      ],
    },
    {
      field: 'items',
      operator: 'ELEMENT_MATCH',
      value: {
        op: 'and',
        children: [
          { field: 'items.sku', operator: 'EQ', value: 'A-1' },
          { field: 'items.qty', operator: 'GT', value: 2 },
        ],
      },
    },
  ],
};

const richView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-rich',
  title: '条件齐全的视图',
  config: recordConfig({ filterMode: 'advanced', filter: RICH }),
};

const simpleView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-simple',
  title: '简单条件',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        { field: 'status', operator: 'IN', value: ['PENDING'] },
        { field: 'amount', operator: 'GTE', value: 100 },
      ],
    },
  }),
};

/**
 * A negated condition (D18-7): "not cancelled" is the status condition
 * wrapped in a `nor` group of its own, which simple mode draws as the pill
 * with its switch pressed.
 */
const negatedView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-negated',
  title: '取反条件',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        {
          op: 'nor',
          children: [{ field: 'status', operator: 'IN', value: ['CANCELLED'] }],
        },
        { field: 'amount', operator: 'GTE', value: 100 },
      ],
    },
  }),
};

/**
 * A numeric `IN`, which takes as many values as the kernel compiles rather
 * than the two a range has ends.
 */
const numberListView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-number-list',
  title: '数值多选',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [{ field: 'amount', operator: 'IN', value: [100, 1200, 5000] }],
    },
  }),
};

/**
 * A condition on a field that carries a time of day (`withTime`): the
 * calendar has a clock under it, one control and one submission. Both boxes
 * start empty, which is the whole day — the start edge is read at
 * `00:00:00.000` and the end edge at `23:59:59.999` (D17-1).
 */
const withTimeView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-with-time',
  title: '带时刻的日期',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'absolute', from: '2026-09-15', to: '2026-09-17' },
        },
      ],
    },
  }),
};

/**
 * 一个仪表盘：它的筛选字段写在**保存下来的配置**里，不是定义里，所以某个
 * kind 从注册表里撤掉之后，旧配置照样还按它提问。数据视图走不到这一步——定义
 * 里有未注册的 kind，整份定义在准入时就被拒（`definition.field.kind-unregistered`）
 * ——仪表盘是这件事真正会发生的地方。
 */
const staleDashboard: ViewInstance = {
  ...savedDashboard,
  id: 'overview-stale',
  title: '类型已下线',
  config: {
    ...dashboardConfig(),
    fields: [
      ...dashboardConfig().fields,
      // 没有任何注册表认得 `swatch`。
      { name: 'tone', label: '色板', kind: 'swatch' },
    ],
    filter: {
      op: 'and',
      children: [
        { field: 'region', operator: 'IN', value: ['CN-EAST'] },
        { field: 'tone', operator: 'EQ', value: '#ff8800' },
      ],
    },
  },
};

/** 全局筛选带里那条只读条件，连同旁边一条照常可编辑的条件。 */
function UnregisteredKindDemo() {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({ instances: [...savedViews, staleDashboard] })
      }
    >
      {engine => (
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId={staleDashboard.id}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

function FilterPanelDemo({
  instanceId,
  hostWidth,
}: {
  instanceId: string;
  /**
   * A column of a given width to render into, for the regression that
   * measures the pills against the room the strip has. The width is fixed at
   * mount rather than written afterwards: the shell decides whether the view
   * list stands beside the work area from the column it is first given, and a
   * column that only *becomes* narrow keeps the list — which leaves a strip
   * too narrow for any pill and measures the fold instead of the pill.
   */
  hostWidth?: number;
}) {
  const workbench = (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [withItems],
          instances: [
            richView,
            simpleView,
            negatedView,
            numberListView,
            withTimeView,
          ],
        })
      }
    >
      {engine => (
        <RecordWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={instanceId}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
  if (hostWidth === undefined) return workbench;
  return (
    <div data-pill-host style={{ width: hostWidth, overflow: 'hidden' }}>
      {workbench}
    </div>
  );
}

const scene = {
  ...viewEngineScene,
  domain: '筛选编辑器',
  summary: '分组是块，条件是内联的 pill；持有谓词的条件和分组一样是块。',
  fixture: '内存 ViewStore · 带商品行数组字段的订单定义',
  setup: '每次挂载都新建引擎与存储。',
  observe:
    '同一分组内每个字段只出现一次，添加条件的菜单只列出尚未使用的字段；未填写的条件是虚线，出错的条件标红。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/数据视图/筛选编辑器',
  component: FilterPanelDemo,
  args: { instanceId: 'orders-rich' },
  argTypes: {
    instanceId: { table: { disable: true } },
    hostWidth: { table: { disable: true } },
  },
} satisfies Meta<typeof FilterPanelDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Every shape of condition at once: pills, a nested group, an element match. */
export const Advanced: Story = { args: { instanceId: 'orders-rich' } };

/** Simple mode: the root's conditions as one strip, nothing else. */
export const Simple: Story = { args: { instanceId: 'orders-simple' } };

/**
 * 简单模式里的取反：展开「筛选」，「状态」那条 pill 的开关是按下的，句子里操作符
 * 前面多了一个「不」；已应用条把它说成「排除 …」。再按一次开关就是原来的条件。
 */
export const Negated: Story = { args: { instanceId: 'orders-negated' } };

/**
 * A numeric `IN` as a list that grows: one chip per value with a remove
 * button of its own, an entry field, and the button that commits it. Unfold
 * 筛选 in the title bar to type a fourth value.
 */
export const NumberList: Story = { args: { instanceId: 'orders-number-list' } };

/**
 * A date condition on a field that carries a time of day. Unfold 筛选 and
 * open the calendar: the clock is under it, in the same popover, and leaving
 * a box empty keeps that end of the range at the day itself — the first
 * millisecond as a start, the last as an end.
 */
export const WithTime: Story = { args: { instanceId: 'orders-with-time' } };

/**
 * 一条没人能编辑的条件。展开「筛选」：色板那条画成只读——字段名、操作符的那个
 * 词、配置里存着的原值，外加一句说明它为什么不能改——✕ 照常可按，「查询」被挡
 * 住并在旁边报出待修正的条数。旁边那条仓库条件一切如常，只读只针对那一条。
 */
export const UnregisteredKind: Story = {
  render: () => <UnregisteredKindDemo />,
};
