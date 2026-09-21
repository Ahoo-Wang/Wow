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
import { RecordWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  ordersDefinition,
  recordConfig,
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

function FilterPanelDemo({ instanceId }: { instanceId: string }) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [withItems],
          instances: [richView, simpleView, numberListView, withTimeView],
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
  argTypes: { instanceId: { table: { disable: true } } },
} satisfies Meta<typeof FilterPanelDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Every shape of condition at once: pills, a nested group, an element match. */
export const Advanced: Story = { args: { instanceId: 'orders-rich' } };

/** Simple mode: the root's conditions as one strip, nothing else. */
export const Simple: Story = { args: { instanceId: 'orders-simple' } };

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
