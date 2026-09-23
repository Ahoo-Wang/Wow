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
import { DashboardWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  dashboardConfig,
  emptyDashboard,
  legacyDashboardConfig,
  savedDashboard,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/** Which saved dashboard a story opens, and which views it can reach. */
type Variant = 'panels' | 'filtered' | 'unavailable' | 'empty' | 'legacy';

/**
 * A dashboard composes saved views. The global filter reaches each panel as
 * an injected scope, mapped onto that panel's own field, so a referenced view
 * never becomes dirty and no dashboard condition is saved back into it.
 */
function DashboardDemo({
  behaviour = 'data',
  variant = 'panels',
  editable = false,
}: {
  behaviour?: SourceBehaviour;
  variant?: Variant;
  editable?: boolean;
}) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: [
            // An unavailable panel is one whose instance is not in the store.
            ...(variant === 'unavailable' ? [savedViews[1]] : savedViews),
            { ...savedDashboard, config: savedConfig(variant) },
          ],
        })
      }
    >
      {engine => (
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId={savedDashboard.id}
          editable={editable}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

function savedConfig(variant: Variant) {
  if (variant === 'empty') return emptyDashboard();
  if (variant === 'legacy') return legacyDashboardConfig();
  if (variant === 'filtered')
    return dashboardConfig({
      filter: {
        op: 'and',
        children: [{ field: 'region', operator: 'IN', value: ['CN-SOUTH'] }],
      },
    });
  return dashboardConfig();
}

/** What the scenes answer from, said in the host's service line and below. */
const FIXTURE = '内存 ViewStore · 两个被引用的共享视图 · 一个内容面板';

/**
 * What the scene is, on the docs page rather than above the workbench: the
 * workbench sits in the host application (`AppShell`), as it would in a
 * product, and has the page area to itself.
 */
const description = `**仪表盘视图 · 仪表盘**

把已保存的明细与分析放在一页，用一个全局筛选统一收口径。

- **数据源**：${FIXTURE}。
- **准备**：每次挂载都新建引擎与存储；面板引用的实例随场景增减。
- **操作**：打开任一场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像使用者看到的一样。
- **观察**：每个面板各自加载、各自出错；一个面板不可用不影响其余面板。`;

const meta = {
  parameters: {
    // The workbench fills the host's page area, as it would a screen.
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="dashboard" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/仪表盘视图/Dashboard',
  component: DashboardDemo,
  args: { behaviour: 'data', variant: 'panels', editable: false },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    variant: { table: { disable: true } },
    editable: { control: 'boolean' },
  },
} satisfies Meta<typeof DashboardDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Two data panels and one content panel, under one global filter. */
export const AllPanels: Story = { args: { variant: 'panels' } };

/**
 * Saved with the global filter on 华南: each panel answers for that warehouse
 * alone, through its own field, and neither referenced view changes.
 */
export const GlobalFilter: Story = { args: { variant: 'filtered' } };

/**
 * Drag by a panel's grip, or resize it by its corner — or do either from the
 * keyboard: both handles answer the arrow keys, and the menu beside the grip
 * says the same eight commands in words. Each one applies at once, like
 * sorting a table.
 */
export const EditableLayout: Story = { args: { editable: true } };

/** A referenced view that was deleted: only that panel says so. */
export const PanelUnavailable: Story = { args: { variant: 'unavailable' } };

/** Each panel loads on its own, so they arrive independently. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failing backend leaves the layout and the filter intact. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/** A dashboard with nothing on it yet. */
export const EmptyDashboard: Story = { args: { variant: 'empty' } };

/**
 * 一块在栅格还是 12 列时存下的仪表盘（没有 `columns`）：打开时按 24 列读，
 * `x`、`w` 乘 2，每个面板落在原来的像素上（D22 E）。不变脏，存回才写新格式。
 */
export const LegacyLayout: Story = {
  name: '旧的 12 列布局',
  args: { variant: 'legacy' },
};
