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
import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  withFieldKinds,
} from '@ahoo-wang/wow-view-engine';
import type {
  DataViewDefinition,
  FieldKind,
  FilterTree,
  RecordViewRuntime,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { useFilterEditor } from '@ahoo-wang/wow-view-engine/react';
import {
  DataWorkbench,
  FilterPanel,
  ViewSurface,
} from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  ordersDefinition,
  recordConfig,
  savedViews,
  storySource,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

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
 * A reference condition (F-04): the customers are searched from the host's
 * `OptionSource`, and the value carries each name beside its id.
 */
const referenceView: ViewInstance = {
  ...savedViews[0],
  id: 'orders-reference',
  title: '客户条件',
  config: recordConfig({
    filter: {
      op: 'and',
      children: [
        {
          field: 'customer',
          operator: 'IN',
          value: { items: [{ id: 'c-03', label: '晨光食品' }] },
        },
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
 * 一个宿主注册了、却要一个引擎没有的编辑器的类型：色板（`swatch`）要的是
 * `colourWheel`，不在 `EDITOR_INPUTS` 里。类型注册了，定义照常准入；这条条件
 * 却没有控件能编——`validateFilter` 以 `filter.kind.unknown-editor` 拒掉它
 * （F-06）。视图由引擎按这份注册表新建，不经工作台，看条件编辑器怎么对待它。
 */
const swatch: FieldKind = {
  ...(builtinFieldKinds.get('string') as FieldKind),
  id: 'swatch',
  editor: () => ({ input: 'colourWheel' as never }),
};

function unknownEditorView(): {
  engine: ViewEngine;
  runtime: RecordViewRuntime;
} {
  const definition: DataViewDefinition = {
    ...ordersDefinition,
    fields: [
      ...ordersDefinition.fields,
      { name: 'tone', label: '色板', kind: 'swatch' },
    ],
  };
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore(),
    resolveSource: () => storySource(),
    kinds: withFieldKinds(builtinFieldKinds, [swatch]),
  });
  const runtime = engine.create(definition.id, {
    title: '色板没有编辑器',
    scope: 'personal',
    config: recordConfig({
      filter: {
        op: 'and',
        children: [
          { field: 'warehouse', operator: 'NOT_IN', value: ['CN-EAST'] },
          { field: 'tone', operator: 'EQ', value: '#ff8800' },
        ],
      },
    }),
  });
  return { engine, runtime };
}

/**
 * 那条只读条件，连同旁边一条照常可编辑的条件，在独立的条件编辑器里：它不需要
 * 工作台，只要一个 runtime，挂载一次、随卸载连同引擎一起释放。
 */
function UnknownEditorDemo() {
  const [view] = useState(unknownEditorView);
  useEffect(() => () => view.engine.dispose(), [view]);
  const filter = useFilterEditor(view.runtime);
  return (
    <ViewSurface {...HOST_LANGUAGE}>
      <FilterPanel filter={filter} />
    </ViewSurface>
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
            referenceView,
            numberListView,
            withTimeView,
          ],
        })
      }
    >
      {engine => (
        <DataWorkbench
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

/** What the scenes answer from, said in the host's service line and below. */
const FIXTURE = '内存 ViewStore · 带商品行数组字段的订单定义';

/**
 * What the scene is, on the docs page rather than above the editor: the
 * editor sits in the host application (`AppShell`), in the workbench a host
 * places in its page area, which is where anyone meets it.
 */
const description = `**数据视图 · 筛选编辑器**

分组是块，条件是内联的 pill；持有谓词的条件和分组一样是块。

- **数据源**：${FIXTURE}。
- **准备**：每次挂载都新建引擎与存储。
- **操作**：打开任一场景，展开标题栏上的「筛选」。编辑器在宿主应用的页面区里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：同一分组内每个字段只出现一次，添加条件的菜单只列出尚未使用的字段；未填写的条件是虚线，出错的条件标红。`;

const meta = {
  parameters: {
    // The workbench fills the host's page area, inside the page's gutter.
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="filters" service={{ fixture: FIXTURE }} padded>
        <Story />
      </AppShell>
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
 * 引用字段的候选是搜出来的：展开「筛选」，「客户」那条 pill 里已选的客户是一枚
 * chip，输入框说「输入以搜索」；点进去先列第一页，打字停下才再问源，尾部有「更多」；
 * 拿掉 chip、再选一个，「应用」后表格随之变。
 */
export const Reference: Story = { args: { instanceId: 'orders-reference' } };

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
 * 一条没人能编辑的条件。色板那条画成只读——字段名、操作符的那个
 * 词、配置里存着的原值，外加一句说明它为什么不能改——✕ 照常可按，「查询」被挡
 * 住并在旁边报出待修正的条数。旁边那条仓库条件一切如常，只读只针对那一条。
 */
export const UnknownEditor: Story = {
  render: () => <UnknownEditorDemo />,
};
