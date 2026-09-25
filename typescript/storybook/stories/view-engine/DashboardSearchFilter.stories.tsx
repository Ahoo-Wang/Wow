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
  DashboardWorkbench,
  EmbeddedDashboard,
} from '@ahoo-wang/wow-view-engine/ui';
import type {
  DashboardPanel,
  DashboardViewConfig,
  DataViewDefinition,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  savedViews,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The orders, with the search box a list page of them has: the order
 * number and the note, matched as words.
 */
const searchableOrders: DataViewDefinition = {
  ...ordersDefinition,
  fields: [
    ...ordersDefinition.fields,
    {
      name: 'q',
      label: '订单号或备注',
      kind: 'search',
      searchFields: ['id', 'note'],
    },
  ],
};

function viewPanel(
  id: string,
  title: string,
  instanceId: string,
  layout: DashboardPanel['layout'],
  bindings: { globalField: string; panelField: string }[] = [],
): DashboardPanel {
  return { id, kind: 'view', title, instanceId, bindings, layout };
}

/**
 * Two record panels — the pending orders and every order — and the
 * warehouse chart between them. With `search`, the board holds a search
 * wired to both lists, as an author saved it.
 */
function searchBoard(search: boolean): DashboardViewConfig {
  const wired = search ? [{ globalField: 'find', panelField: 'q' }] : [];
  return dashboardConfig({
    fields: search ? [{ name: 'find', label: '搜索订单', kind: 'search' }] : [],
    panels: [
      viewPanel(
        'pending',
        '待出库明细',
        'orders-pending',
        { x: 0, y: 0, w: 14, h: 4 },
        wired,
      ),
      viewPanel('by-warehouse', '按仓库汇总', 'orders-analysis', {
        x: 14,
        y: 0,
        w: 10,
        h: 4,
      }),
      viewPanel(
        'all',
        '全部订单',
        'orders-all',
        { x: 0, y: 4, w: 24, h: 4 },
        wired,
      ),
    ],
  });
}

/** Every order, newest first: the second list a search finds rows in. */
const allOrders: ViewInstance = {
  id: 'orders-all',
  definitionId: 'orders',
  title: '全部订单',
  scope: 'shared',
  revision: '1',
  config: recordConfig({ sort: [{ field: 'createdAt', direction: 'DESC' }] }),
};

function board(search: boolean): ViewInstance {
  return {
    id: 'search-board',
    definitionId: 'overview',
    title: '订单查找',
    scope: 'personal',
    revision: '1',
    config: searchBoard(search),
  };
}

type Scene = 'build' | 'embed';

function SearchFilterDemo({ scene }: { scene: Scene }) {
  return (
    <StoryEngine
      key={scene}
      create={() =>
        createStoryEngine({
          definitions: [searchableOrders, overviewDefinition],
          instances: [...savedViews, allOrders, board(scene === 'embed')],
        })
      }
    >
      {engine =>
        scene === 'build' ? (
          <DashboardWorkbench
            engine={engine}
            definitionId="overview"
            instanceId="search-board"
            {...HOST_LANGUAGE}
          />
        ) : (
          <EmbeddedDashboard
            engine={engine}
            instanceId="search-board"
            interaction="interactive"
            {...HOST_LANGUAGE}
          />
        )
      }
    </StoryEngine>
  );
}

const FIXTURE =
  '内存 ViewStore · 订单定义声明了搜索框（订单号、备注）· 两个明细、一个分析';

const description = `**仪表盘视图 · 板上的搜索**

运营日报一类的板子要一个找订单的框：「添加筛选 → 搜索」加一枚，接到明细面板的搜索框上。

- **数据源**：${FIXTURE}。
- **操作**：按「编辑」→「添加筛选」→「搜索」，在设置里按「接线」，在「待出库明细」上选「订单号或备注」——「全部订单」自动接上，「按仓库汇总」说没有可接的字段；「完成接线」后在筛选条上打「SO-1001」。
- **观察**：空着时框里写「搜索…」；打字即跑，两个明细一起收窄，分析面板不动。`;

const meta = {
  title: 'View Engine/能力/板上的搜索',
  component: SearchFilterDemo,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="board-search" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  args: { scene: 'build' },
  argTypes: { scene: { table: { disable: true } } },
} satisfies Meta<typeof SearchFilterDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * A board with no filter yet, in the workbench: press 「编辑」 and add a
 * search from 「添加筛选」, then wire it — the record panels' search boxes
 * take it, the chart does not.
 */
export const BuildASearch: Story = {
  name: '搭一个搜索',
  args: { scene: 'build' },
};

/**
 * The same board saved with its search, embedded at the `interactive` tier
 * (D36): the reader types into it and both lists narrow; nothing is saved.
 */
export const SearchInAnEmbed: Story = {
  name: '嵌入里搜索',
  args: { scene: 'embed' },
};
