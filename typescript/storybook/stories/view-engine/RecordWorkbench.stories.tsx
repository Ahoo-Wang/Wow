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
import { MemoryViewStore } from '@ahoo-wang/fetcher-view-engine';
import type { RecordActionSlots } from '@ahoo-wang/fetcher-view-engine/react';
import { RecordWorkbench, zhCN } from '@ahoo-wang/fetcher-view-engine/ui';
// View Engine's own button, so the host's commands sit in its toolbar rather
// than beside it — exactly what an application does with the action slots.
import { Button } from '@/ui/components/button';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  createStoryEngine,
  recordConfig,
  savedViews,
  tableSettingsStore,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The Record workbench in one state at a time. Every state below follows from
 * what the backend does or from what the saved config says, so a story sets
 * one of those two and changes nothing else.
 */
/**
 * Six orders two at a time, oldest first: the one scenario where the bar
 * under the rows has somewhere to go. The saved size is below the offered
 * ladder, so the control also shows a size folding into it.
 */
const pagedView = {
  ...savedViews[0],
  title: '逐页翻看',
  config: recordConfig({
    pageSize: 2,
    sort: [{ field: 'createdAt', direction: 'ASC' as const }],
  }),
};

function RecordWorkbenchDemo({
  behaviour = 'data',
  instanceId,
  broken = false,
  paged = false,
  withActions = false,
  localized = false,
  keepStore = false,
  collapsed = false,
}: {
  behaviour?: SourceBehaviour;
  instanceId?: string;
  /** Saves a config the definition no longer accepts, to show "needs fixing". */
  broken?: boolean;
  /** Saves a page size small enough that the result spans several pages. */
  paged?: boolean;
  /** Fills the three action slots, the way a business page would. */
  withActions?: boolean;
  /** Hands the workbench the shipped Chinese catalogue. */
  localized?: boolean;
  /** Publishes the store on `tableSettingsStore`, for a play to read. */
  keepStore?: boolean;
  /** Opens with the view list folded away, as a narrow page would. */
  collapsed?: boolean;
}) {
  return (
    <StoryEngine
      create={() => {
        const store = keepStore
          ? new MemoryViewStore({ instances: savedViews })
          : undefined;
        if (store) tableSettingsStore.current = store;
        return createStoryEngine({
          behaviour,
          ...(store ? { store } : {}),
          instances: broken
            ? [
                {
                  ...savedViews[0],
                  title: '待修复视图',
                  config: recordConfig({
                    table: { columns: [{ field: 'removedColumn' }] },
                  }),
                },
              ]
            : paged
              ? [pagedView]
              : savedViews,
        });
      }}
    >
      {engine => (
        <RecordWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={instanceId ?? savedViews[0].id}
          actions={withActions ? businessActions : undefined}
          messages={localized ? zhCN : undefined}
          defaultSidebarOpen={!collapsed}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What an application hangs on the workbench: one command over the view, one
 * over a selection, one per row. They are render functions rather than names
 * in a config, so they can do anything the page can do — and nothing about
 * them is saved with the view.
 */
const businessActions: RecordActionSlots = {
  global: () => (
    <Button size="sm" onClick={() => alert('新建订单')}>
      新建订单
    </Button>
  ),
  bulk: ({ rows, clearSelection }) => (
    <Button
      variant="outline"
      size="sm"
      onClick={() => {
        alert(`导出 ${rows.length} 单`);
        clearSelection();
      }}
    >
      导出所选
    </Button>
  ),
  row: ({ row, refresh }) => (
    <>
      <Button
        variant="ghost"
        size="xs"
        onClick={() => alert(`打开 ${row.key}`)}
      >
        打开
      </Button>
      <Button
        variant="ghost"
        size="xs"
        onClick={() => {
          alert(`取消 ${row.key}`);
          refresh();
        }}
      >
        取消
      </Button>
    </>
  ),
};

const scene = {
  ...viewEngineScene,
  domain: '数据视图',
  summary: '明细、汇总、筛选与保存，全部来自一份配置。',
  fixture: '内存 ViewStore · 六条订单 · 可切换的数据源行为',
  setup: '每次挂载都新建引擎与存储，场景之间不共享已保存的视图。',
  observe: '表格、汇总行与提示反映这一次执行的口径，而不是草稿。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/数据视图/Record 工作台',
  component: RecordWorkbenchDemo,
  args: { behaviour: 'data' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing', 'no-aggregate'],
    },
    broken: { table: { disable: true } },
    paged: { table: { disable: true } },
    instanceId: { table: { disable: true } },
    withActions: { table: { disable: true } },
    localized: { table: { disable: true } },
    keepStore: { table: { disable: true } },
    collapsed: { table: { disable: true } },
  },
} satisfies Meta<typeof RecordWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Rows, the summary row and the saved conditions of a shared view. */
export const WithData: Story = { args: { behaviour: 'data' } };

/** A query that succeeded and matched nothing, which is not an error. */
export const EmptyResult: Story = { args: { behaviour: 'empty' } };

/** What the first execution looks like before the answer arrives. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failed query keeps the view and its conditions; only the data is gone. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/**
 * 汇总查询失败，明细照常。合计因此退回本页合计——数字留着，因为本页合计本身
 * 有用——但行尾的口径标签改说「本页」，上方多一条 warning 说明为什么。默默
 * 顶替才是这里唯一的错误：读者看到「总计」，会当成全部命中记录的总计。
 */
export const TotalCoversThisPageOnly: Story = {
  args: { behaviour: 'no-aggregate' },
};

/** A saved config the definition outgrew: `apply` is refused until it is fixed. */
export const NeedsFixing: Story = { args: { broken: true } };

/** No saved view under this id, reported instead of an empty frame. */
export const CannotOpen: Story = { args: { instanceId: 'deleted' } };

/**
 * 结果不止一页时，表格下面那一行：左边说一共多少条，右边是每页几条、第几页，
 * 以及前后两步。翻页不写进配置，改每页条数则是一次编辑，会立刻应用。
 */
export const Paged: Story = { args: { paged: true } };

/**
 * The host's own commands in the three places they belong: over the view, over
 * a selection, and on one row. Pick rows to see the middle one appear.
 */
export const WithActions: Story = { args: { withActions: true } };

/**
 * Managing the list rather than looking at one view: rename, delete, reorder
 * and choose which view opens first. Open it from the gear beside the sidebar
 * heading — every button is there only where the store permits it.
 */
export const ManageViews: Story = { args: { behaviour: 'data' } };

/**
 * 中文文案。包里带了 `zhCN`，宿主把它交给 `messages` 就换掉整面的措辞；要改其
 * 中几句，铺开再覆盖：`{ ...zhCN, 'label.filter.apply': '确定' }`。
 *
 * 打开的是那个带条件的共享视图，所以结果上方的「正在显示」里就有一枚可操作的
 * 条件 badge：字段名来自定义，操作符与候选项标签分别来自目录与定义，按 ✕ 把它
 * 撤下会立刻重跑查询。展开筛选带还能看到相对日期的单位与时间段——`day`、
 * `thisWeek` 这些以前是原样的标识符，现在同样走目录。
 */
export const Localized: Story = { args: { localized: true } };

/**
 * 表格设置：工具栏右端的「列设置」与「排序」。
 *
 * 列设置里一行一列——拖动手柄、显隐、列名、汇总函数、固定开关。拖动只在同一区域
 * 内生效：主键「订单号」钉在左侧，宿主的操作列钉在右侧（这个故事没有行动作，所以
 * 右侧那一行不出现），中间几列随意排。手柄也可以用键盘：Tab 到手柄，方向键上下移
 * 一位，移完会播报落在第几位。旁边的排序按钮把当前排序读成话，点开可以逐条翻方
 * 向、删掉，或者添加一个还没用到的可排序字段。改完点「Save」，重开这个视图就是
 * 现在这副样子——列设置与排序改的都是视图本身，不是这一次打开。
 */
export const TableSettings: Story = { args: { keepStore: true } };

/**
 * 侧栏收起后的样子：标题栏最左边是展开按钮、定义标题与视图切换下拉，结果拿回
 * 侧栏占掉的那点宽度。下拉按受众分组、当前项打勾、系统视图带标签，末尾是「管理
 * 视图」——和侧栏齿轮开的是同一个对话框。切换照样先过离开守卫。
 */
export const CollapsedSidebar: Story = { args: { collapsed: true } };
