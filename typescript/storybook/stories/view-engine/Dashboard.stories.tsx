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
import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import type {
  DashboardFilters,
  DashboardViewConfig,
  DataViewDefinition,
  ViewInstance,
} from '@ahoo-wang/fetcher-view-engine';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  analysisConfig,
  createStoryEngine,
  dashboardConfig,
  emptyDashboard,
  legacyDashboardConfig,
  ordersDefinition,
  overviewDefinition,
  savedDashboard,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/** Which saved dashboard a story opens, and which views it can reach. */
type Variant =
  | 'panels'
  | 'filtered'
  | 'unavailable'
  | 'empty'
  | 'empty-shared'
  | 'legacy'
  | 'system'
  | 'owned'
  | 'tabs'
  | 'filters';

/**
 * The board that ships with the definition: read-only to everyone (D4), so
 * it offers 另存为 and no 编辑.
 */
const systemOverview = {
  ...overviewDefinition,
  views: [{ id: 'ops', title: '出库概览（系统）', config: dashboardConfig() }],
};

/**
 * A dashboard composes saved views. The global filter reaches each panel as
 * an injected scope, mapped onto that panel's own field, so a referenced view
 * never becomes dirty and no dashboard condition is saved back into it.
 */
function DashboardDemo({
  behaviour = 'data',
  variant = 'panels',
  onFiltersChange,
}: {
  behaviour?: SourceBehaviour;
  variant?: Variant;
  /** Told what the board's filters hold, as a host's address would be. */
  onFiltersChange?(filters: DashboardFilters): void;
}) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          definitions: [
            // Orders bucketed by time, so a trend can be grouped by the
            // board's 按日｜按月 (D22 F).
            timedOrdersDefinition,
            variant === 'system' ? systemOverview : overviewDefinition,
          ],
          instances: [
            trendView,
            // An unavailable panel is one whose instance is not in the store.
            ...(variant === 'unavailable' ? [savedViews[1]] : savedViews),
            {
              ...savedDashboard,
              ...(variant === 'empty-shared' || variant === 'owned'
                ? { scope: 'shared' }
                : {}),
              config: savedConfig(variant),
            },
            // A second board beside the tabbed one, so the list has another
            // to open and the tabbed one can be opened again from it.
            ...(variant === 'tabs' ? [otherBoard] : []),
          ],
        })
      }
    >
      {engine => (
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId={
            variant === 'system' ? 'system:overview:ops' : savedDashboard.id
          }
          onFiltersChange={onFiltersChange}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

function savedConfig(variant: Variant) {
  if (variant === 'filters') return filtersConfig();
  if (variant === 'tabs') return tabbedConfig();
  if (variant === 'owned') return ownedConfig();
  if (variant === 'empty' || variant === 'empty-shared')
    return emptyDashboard();
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

/**
 * Two tabs: the outbound overview, and a second tab with one more analysis
 * — only the tab on screen runs (D22 E).
 */
function tabbedConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  return {
    ...base,
    tabs: [
      { id: 'tab-outbound', title: '出库' },
      { id: 'tab-status', title: '状态' },
    ],
    panels: [
      ...base.panels.map(panel => ({ ...panel, tab: 'tab-outbound' })),
      {
        id: 'by-status',
        kind: 'view',
        title: '按状态看金额',
        // An analysis of the board's own, grouped by status, so the title
        // says what the chart draws.
        owned: {
          definitionId: 'orders',
          config: analysisConfig({
            groups: [{ alias: 'status', field: 'status', type: 'TERMS' }],
            chart: {
              type: 'bar',
              cartesian: { x: 'status', series: [{ metric: 'amount' }] },
            },
          }),
        },
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 0, w: 12, h: 4 },
        tab: 'tab-status',
      },
    ],
  };
}

/** The overview with one analysis the board owns rather than refers to (D22 C). */
function ownedConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  return {
    ...base,
    panels: [
      ...base.panels,
      {
        id: 'owned',
        kind: 'view',
        title: '本板自建：订单数按仓库',
        owned: {
          definitionId: 'orders',
          config: analysisConfig({
            chart: {
              type: 'bar',
              cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
            },
          }),
        },
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 8, y: 4, w: 16, h: 4 },
      },
    ],
  };
}

/**
 * Orders whose creation time an analysis may bucket by the day or the month,
 * and whose order numbers it may count by value — which is what a text
 * filter wired to 订单号 offers to pick from, with how many records hold
 * each (D22 G).
 */
const timedOrdersDefinition: DataViewDefinition = {
  ...ordersDefinition,
  analysis: {
    ...ordersDefinition.analysis!,
    fields: [
      ...ordersDefinition.analysis!.fields,
      { field: 'id', groups: [AggregationGroupType.TERMS], functions: [] },
      {
        field: 'createdAt',
        groups: [AggregationGroupType.DATE_HISTOGRAM],
        functions: [],
        dateUnits: [AggregationDateUnit.DAY, AggregationDateUnit.MONTH],
      },
    ],
  },
};

/** Orders by the day they were created: a trend the board's grouping moves. */
const trendView: ViewInstance = {
  id: 'orders-trend',
  definitionId: 'orders',
  title: '每日订单',
  scope: 'shared',
  revision: '1',
  config: analysisConfig({
    groups: [
      {
        alias: 'createdAt',
        field: 'createdAt',
        type: 'DATE_HISTOGRAM',
        unit: 'DAY',
      },
    ],
    chart: {
      type: 'bar',
      cartesian: { x: 'createdAt', series: [{ metric: 'orders' }] },
    },
  }),
};

/**
 * The board's filters (D22 F): a required creation time on every panel, a
 * warehouse on the two over it — not on the trend, which says so — a
 * category 状态 wired to the orders' status, whose closed list it picks from
 * by label, an 订单号 picked from the order numbers the data holds, and the
 * time grouping by the day or the month.
 */
function filtersConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  const created = { globalField: 'created', panelField: 'createdAt' };
  const status = { globalField: 'phase', panelField: 'status' };
  return {
    ...base,
    fields: [
      {
        name: 'created',
        label: '创建时间',
        kind: 'datetime',
        required: true,
        default: {
          type: 'absolute',
          from: '2026-09-01',
          to: '2026-09-30',
        },
      },
      ...base.fields,
      { name: 'phase', label: '状态', kind: 'string' },
      { name: 'order', label: '订单号', kind: 'string' },
    ],
    timeGrouping: { units: ['DAY', 'MONTH'], default: 'DAY' },
    panels: [
      ...base.panels.map(panel =>
        panel.kind === 'view'
          ? {
              ...panel,
              bindings: [
                ...panel.bindings,
                created,
                status,
                ...(panel.id === 'pending'
                  ? [{ globalField: 'order', panelField: 'id' }]
                  : []),
              ],
            }
          : panel,
      ),
      {
        id: 'trend',
        kind: 'view',
        title: '每日订单',
        instanceId: 'orders-trend',
        bindings: [created, status],
        layout: { x: 8, y: 4, w: 16, h: 4 },
      },
    ],
  };
}

/** Another board in the list, for the tabbed one to be left and opened again. */
const otherBoard = {
  ...savedDashboard,
  id: 'overview-other',
  title: '异常概览',
  config: dashboardConfig(),
};

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
  args: { behaviour: 'data', variant: 'panels' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    variant: { table: { disable: true } },
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
 * Press 「编辑」 to build the board (D22 A): the edit bar comes up with
 * 「＋ 添加」, 取消 and 完成, every panel's 「⋯」 gains 「改」, and panels can be
 * dragged by their grip or resized by their corner — or either from the
 * keyboard: both handles answer the arrow keys, and the menu beside the grip
 * says the same eight commands in words. Panels run as the board changes;
 * 完成 saves, 取消 puts back what was saved.
 */
export const Building: Story = { args: { variant: 'panels' } };

/**
 * A board shared with everyone and nothing on it yet: its first steps are
 * offered under the empty state, and a personal view put on it is marked
 * 「只有你看得到」 in the picker (D22 B).
 */
export const EmptySharedBoard: Story = { args: { variant: 'empty-shared' } };

/**
 * A board that owns one analysis (D22 C): while it is built, its 「⋯」 offers
 * 「另存为视图…」, which makes it a view of its own; 「＋ 添加 ▾」 offers
 * 「新建分析…」, the analysis view in a dialog; and an analysis panel offers
 * 「改这里的展示…」, its own look (D22 D).
 */
export const OwnedAnalysis: Story = {
  name: '板内分析与改展示',
  args: { variant: 'owned' },
};

/**
 * Two tabs (D22 E): only the tab on screen runs, the reader's last tab is
 * where the board opens next, and while it is built the bar adds, renames,
 * reorders and deletes tabs.
 */
export const Tabs: Story = { name: '标签页', args: { variant: 'tabs' } };

/** The board the definition ships: 另存为, and no 编辑 (D4). */
export const SystemDashboard: Story = { args: { variant: 'system' } };

/** A referenced view that was deleted: only that panel says so. */
export const PanelUnavailable: Story = { args: { variant: 'unavailable' } };

/** Each panel loads on its own, so they arrive independently. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failing backend leaves the layout and the filter intact. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/**
 * The filter bar (D22 F): 创建时间 is required — starred, never empty —
 * 仓库 reaches the two panels over it and not the trend, which says so once
 * 仓库 holds a value, and 按日｜按月 regroups the trend. While the board is
 * built, 「筛选 ＋」 adds one, and 「接线」 wires it (D22 G).
 */
export const Filters: Story = { name: '筛选条', args: { variant: 'filters' } };

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
