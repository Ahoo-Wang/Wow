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
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  DashboardWorkbench,
  DataWorkbench,
  EmbeddedDashboard,
} from '@ahoo-wang/wow-view-engine/ui';
import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import type {
  DashboardFilters,
  ViewNavigation,
  DashboardViewConfig,
  DataViewDefinition,
  ViewEngine,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
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
import '@ahoo-wang/wow-view-engine/styles.css';

/** Which saved dashboard a story opens, and which views it can reach. */
type Variant =
  | 'panels'
  | 'filtered'
  | 'unavailable'
  | 'empty'
  | 'empty-shared'
  | 'personal'
  | 'legacy'
  | 'pre-c'
  | 'system'
  | 'owned'
  | 'tabs'
  | 'filters'
  | 'clicks'
  | 'cross'
  | 'to-board'
  | 'to-board-stale'
  | 'to-board-own'
  | 'handed'
  | 'deleted';

/** The variants whose host has a route off the board (D22 H, I). */
const ROUTED: readonly Variant[] = [
  'handed',
  'clicks',
  'cross',
  'to-board',
  'to-board-stale',
  'to-board-own',
];

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
  onNavigate,
}: {
  behaviour?: SourceBehaviour;
  variant?: Variant;
  /** Told what the board's filters hold, as a host's address would be. */
  onFiltersChange?(filters: DashboardFilters): void;
  /** Told where a way off the board goes, as a host's router would be. */
  onNavigate?(to: ViewNavigation): void;
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
              ...(variant === 'empty-shared' ||
              variant === 'owned' ||
              variant === 'personal'
                ? { scope: 'shared' }
                : {}),
              config: savedConfig(variant),
            },
            // A second board beside the tabbed one, so the list has another
            // to open and the tabbed one can be opened again from it.
            ...(variant === 'tabs' ? [otherBoard] : []),
            // The board a press can open (D23 Q17).
            ...(ROUTED.includes(variant) ? [regionalBoard] : []),
          ],
        })
      }
    >
      {engine =>
        ROUTED.includes(variant) ? (
          <RoutedHost
            engine={engine}
            holdsState={variant === 'handed'}
            onFiltersChange={onFiltersChange}
            onNavigate={onNavigate}
          />
        ) : (
          <DashboardWorkbench
            engine={engine}
            definitionId="overview"
            instanceId={
              variant === 'system'
                ? 'system:overview:ops'
                : // A board the store no longer holds.
                  variant === 'deleted'
                  ? 'deleted'
                  : savedDashboard.id
            }
            // The 'filtered' board opens with 仓库 set to 华南 on its filter
            // bar, as a host's address would hand it over (D27): the
            // reader's value, not a condition saved in the board.
            initialFilters={variant === 'filtered' ? SOUTH : undefined}
            onFiltersChange={onFiltersChange}
            {...HOST_LANGUAGE}
          />
        )
      }
    </StoryEngine>
  );
}

/**
 * A host with a route (D22 H, I): the board, and where a way off it goes —
 * the workbench, opened on what the board handed over (a saved view, or a
 * view nobody saved: a follow-up), another board, or a page of its own.
 * The workbench draws its own way back to the board (D26 Q33), which comes
 * here as one more route: the board, under the filters it was left on — as
 * a host's address would keep them. Another board and a page of the
 * host's are the host's to leave, so those two keep a back button of its
 * own.
 *
 * `holdsState` puts the board on a page that holds one filter — 状态 locked
 * to what is still in play — as an embed's page does (D24).
 */
function RoutedHost({
  engine,
  holdsState = false,
  onFiltersChange,
  onNavigate,
}: {
  engine: ViewEngine;
  holdsState?: boolean;
  onFiltersChange?(filters: DashboardFilters): void;
  onNavigate?(to: ViewNavigation): void;
}) {
  const [away, setAway] = useState<ViewNavigation | null>(null);
  const [filters, setFilters] = useState<DashboardFilters | undefined>();
  const [tab, setTab] = useState<string | null | undefined>();
  const route = (to: ViewNavigation) => {
    onNavigate?.(to);
    if (to.kind === 'dashboard' && to.instanceId === savedDashboard.id) {
      // The way back: this board, as it was left.
      setFilters(to.filters);
      setTab(to.tab);
      setAway(null);
    } else setAway(to);
  };
  const reader = {
    initialFilters: filters,
    onFiltersChange: (next: DashboardFilters) => {
      setFilters(next);
      onFiltersChange?.(next);
    },
    onNavigate: route,
    ...HOST_LANGUAGE,
  };
  if (away === null)
    return holdsState ? (
      <EmbeddedDashboard
        engine={engine}
        instanceId={savedDashboard.id}
        interaction="interactive"
        withTitle
        filterModes={{ state: 'locked' }}
        pageValues={{ values: { state: IN_PLAY } }}
        {...reader}
      />
    ) : (
      <DashboardWorkbench
        engine={engine}
        definitionId="overview"
        instanceId={savedDashboard.id}
        initialTab={tab}
        {...reader}
      />
    );
  if (away.kind === 'view' || away.kind === 'unsaved')
    return (
      <DataWorkbench
        engine={engine}
        definitionId={away.definitionId}
        handOver={away}
        onNavigate={route}
        {...HOST_LANGUAGE}
      />
    );
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <button
        type="button"
        data-slot="host-back"
        style={{ alignSelf: 'flex-start' }}
        onClick={() => setAway(null)}
      >
        ← 回到出库概览
      </button>
      {away.kind === 'url' ? (
        <p data-slot="host-page">宿主页面：{away.url}</p>
      ) : (
        // Another board, opened with the values the press carried as its
        // reader's — the host's address would hold them the same way.
        <DashboardWorkbench
          key={away.instanceId}
          engine={engine}
          definitionId={away.definitionId}
          instanceId={away.instanceId}
          initialFilters={away.filters}
          {...HOST_LANGUAGE}
        />
      )}
    </div>
  );
}

/** 仓库 at 华南, the reader's value the 'filtered' board opens on. */
const SOUTH: DashboardFilters = { values: { region: ['CN-SOUTH'] } };

/** What the page of the 'handed' board holds 状态 to: the orders in play. */
const IN_PLAY = ['PENDING', 'SHIPPED'];

/**
 * The outbound overview with a second filter, 状态, wired to both data
 * panels — the one the page of the 'handed' variant locks.
 */
function handedConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'state',
        label: '状态',
        kind: 'enum',
        multiple: true,
        options: [
          { value: 'PENDING', label: '待出库' },
          { value: 'SHIPPED', label: '已发运' },
          { value: 'CANCELLED', label: '已取消' },
        ],
      },
    ],
    panels: base.panels.map(panel =>
      panel.kind === 'view'
        ? {
            ...panel,
            bindings: [
              ...(panel.bindings ?? []),
              { globalField: 'state', panelField: 'status' },
            ],
          }
        : panel,
    ),
  };
}

function savedConfig(variant: Variant) {
  if (variant === 'filters') return filtersConfig();
  if (variant === 'cross')
    return dashboardConfig({
      panels: dashboardConfig().panels.map(panel =>
        panel.id === 'by-warehouse'
          ? { ...panel, click: { kind: 'filter', filter: 'region' } }
          : panel,
      ),
    });
  if (variant === 'to-board' || variant === 'to-board-stale')
    return dashboardConfig({
      panels: dashboardConfig().panels.map(panel =>
        panel.id === 'by-warehouse'
          ? {
              ...panel,
              click: {
                kind: 'dashboard',
                instanceId: regionalBoard.id,
                // The stale one maps a filter the board no longer has.
                values:
                  variant === 'to-board'
                    ? { region: { dimension: 'warehouse' } }
                    : { zone: { dimension: 'warehouse' } },
              },
            }
          : panel,
      ),
    });
  if (variant === 'to-board-own') return ownFilterConfig();
  if (variant === 'handed') return handedConfig();
  if (variant === 'tabs') return tabbedConfig();
  if (variant === 'owned') return ownedConfig();
  if (variant === 'empty' || variant === 'empty-shared')
    return emptyDashboard();
  if (variant === 'legacy') return legacyDashboardConfig();
  if (variant === 'pre-c') return preBatchCConfig();
  if (variant === 'personal') return personalPanelConfig();
  return dashboardConfig();
}

/**
 * The outbound overview as a store kept it before batch C (D23 Q16, D26
 * Q31): one board condition in `filter`, and no `fixed`. 「仓库 属于 华南」
 * is what the 仓库 filter asks, so it is read as that filter's default;
 * 「仓库 不是 西南」 asks with another operator, so no filter can hold it and
 * it is read as the board's fixed scope. The `filter` member itself is read
 * out of the config: a board has none of its own (D27).
 */
function preBatchCConfig(): DashboardViewConfig {
  const stored: Record<string, unknown> = {
    ...dashboardConfig(),
    filter: {
      op: 'and',
      children: [
        { field: 'region', operator: 'IN', value: ['CN-SOUTH'] },
        { field: 'region', operator: 'NOT_IN', value: ['CN-WEST'] },
      ],
    },
    filterMode: 'simple',
  };
  delete stored.fixed;
  return stored as unknown as DashboardViewConfig;
}

/**
 * The outbound overview, shared, with one more panel under it on the
 * author's own 「我盯的大额单」 — a personal view, so every other reader of
 * the board sees nothing there (D22 B).
 */
function personalPanelConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  return {
    ...base,
    panels: [
      ...base.panels,
      {
        id: 'mine',
        kind: 'view',
        instanceId: 'orders-mine',
        bindings: [{ globalField: 'region', panelField: 'warehouse' }],
        layout: { x: 0, y: 8, w: 24, h: 4 },
      },
    ],
  };
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

/**
 * The board a press on 「按仓库汇总」 can open (D23 Q17): the same panels,
 * its 仓库 and a 状态 of its own — which no dimension of that panel can
 * fill, so its row offers only 「不带」.
 */
const regionalBoard: ViewInstance = {
  ...savedDashboard,
  id: 'overview-regional',
  title: '区域明细',
  scope: 'shared',
  config: dashboardConfig({
    fields: [
      ...dashboardConfig().fields,
      { name: 'placed', label: '下单时间', kind: 'datetime' },
    ],
    panels: dashboardConfig().panels.map(panel =>
      panel.kind === 'view'
        ? {
            ...panel,
            bindings: [
              ...panel.bindings,
              { globalField: 'placed', panelField: 'createdAt' },
            ],
          }
        : panel,
    ),
  }),
};

/**
 * This board with a 下单时间 of its own (September, by default) over both
 * panels, and 「按仓库汇总」 set to open 「区域明细」 carrying two things
 * (D23 Q17): its 仓库 from the bar pressed, its 下单时间 from this board's.
 */
function ownFilterConfig(): DashboardViewConfig {
  const base = dashboardConfig();
  const placed = { globalField: 'placed', panelField: 'createdAt' };
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        name: 'placed',
        label: '下单时间',
        kind: 'datetime',
        default: { type: 'absolute', from: '2026-09-01', to: '2026-09-30' },
      },
    ],
    panels: base.panels.map(panel =>
      panel.kind !== 'view'
        ? panel
        : {
            ...panel,
            bindings: [...panel.bindings, placed],
            ...(panel.id === 'by-warehouse'
              ? {
                  click: {
                    kind: 'dashboard' as const,
                    instanceId: regionalBoard.id,
                    values: {
                      region: { dimension: 'warehouse' },
                      placed: { filter: 'placed' },
                    },
                  },
                }
              : {}),
          },
    ),
  };
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

/** Two data panels and one content panel, under one filter. */
export const AllPanels: Story = { args: { variant: 'panels' } };

/**
 * The filter bar's 仓库 set to 华南: each panel answers for that warehouse
 * alone, through its own field, and neither referenced view changes. No
 * 「正在显示」 band (D27): the bar is what the panels are showing.
 */
export const GlobalFilter: Story = { args: { variant: 'filtered' } };

/**
 * Press 「编辑」 to build the board (D22 A): the edit bar comes up with
 * 「＋ 添加」, 取消 and 保存, every panel's 「⋯」 gains 「改」, and panels can be
 * dragged by their grip or resized by their corner — or either from the
 * keyboard: both handles answer the arrow keys, and the menu beside the grip
 * says the same eight commands in words. Panels run as the board changes;
 * 保存 saves, 取消 puts back what was saved.
 */
export const Building: Story = { args: { variant: 'panels' } };

/**
 * A board shared with everyone and nothing on it yet: its first steps are
 * offered under the empty state, and a personal view put on it is marked
 * 「只有你看得到」 in the picker (D22 B).
 */
export const EmptySharedBoard: Story = { args: { variant: 'empty-shared' } };

/**
 * A shared board with a panel on the author's personal view (D22 B): the
 * panel wears the warning that its view is not open to every reader, and
 * while the board is built its 「⋯」 offers 「复制为共享视图并替换…」 — the
 * view copied as a shared one, the panel pointed at the copy, looking and
 * filtering as before. Every record panel's 「⋯」 also offers 「导出数据…」,
 * the workbench's export window over its rows.
 */
export const PersonalViewOnSharedBoard: Story = {
  name: '共享板上的个人视图',
  args: { variant: 'personal' },
};

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
 * built, 「添加筛选」 adds one, and 「接线」 wires it (D22 G).
 */
export const Filters: Story = { name: '筛选条', args: { variant: 'filters' } };

/**
 * Pressing a group (D22 H): a bar of 「按仓库汇总」 or a row of its table
 * opens the analysis view's follow-up menu, headed by the group and the
 * board's filters over it; each item opens in the workbench (↗) through the
 * host's route — here the page swaps the board for a `DataWorkbench` on the
 * view nobody saved, with a way back.
 */
export const Clicks: Story = {
  name: '点击：追问菜单',
  args: { variant: 'clicks' },
};

/**
 * Cross-filtering (D22 I): 「按仓库汇总」 is set to update 「仓库」 on a
 * press. A bar pressed sets the filter bar (「来自「按仓库汇总」」) and the
 * list runs under it; the chart keeps every bar and marks the one pressed;
 * the same bar again clears it.
 */
export const CrossFilter: Story = {
  name: '点击：交叉筛选',
  args: { variant: 'cross' },
};

/**
 * Another board (D23 Q17): 「按仓库汇总」 is set to open 「区域明细」 with
 * its 仓库 taken from the bar pressed. The host opens that board with the
 * value as its reader's — the filter bar shows it, its list runs under it —
 * and nothing is written into either board.
 */
export const ToAnotherBoard: Story = {
  name: '点击：去另一块仪表盘',
  args: { variant: 'to-board' },
};

/**
 * The same click, mapped to a filter 「区域明细」 no longer has: a press
 * says so and opens the follow-up menu instead, and the panel warns from
 * then on.
 */
export const ToAnotherBoardStale: Story = {
  name: '点击：去另一块仪表盘（映射失效）',
  args: { variant: 'to-board-stale' },
};

/**
 * Another board, carrying this board's filter too (D23 Q17, 2026-09-23):
 * 「区域明细」 opens with 仓库 from the bar pressed and 下单时间 as this
 * board's holds it.
 */
export const ToAnotherBoardWithOwnFilter: Story = {
  name: '点击：去另一块仪表盘（带这块板的筛选）',
  args: { variant: 'to-board-own' },
};

/**
 * 批 C 之前存下的整板条件（D23 Q16、D26 Q31）：筛选收得下的「仓库 属于 华南」
 * 读成仓库筛选的默认值，筛选条上就是华南、读者能改；收不下的「仓库 不是 西南」
 * 读成仪表盘的固定范围，在筛选条那一行只读地写作「固定范围」，没有 ✕（D27）。
 * 打开不变脏，存回才写新形式（`fixed`），之后作者怎么改筛选设置都不再迁。
 */
export const PreBatchCCondition: Story = {
  name: '批 C 之前的整板条件',
  args: { variant: 'pre-c' },
};

/**
 * Leaving the board (D26 Q30, Q33): the page holds 状态 to the orders still
 * in play (locked); set 仓库 to 华南 and 「⋯ → 在工作台中打开」 「待出库明细」.
 * The workbench opens 「待出库订单」 「已修改」: 仓库 是 华南 is its own
 * condition, with a ✕, while the page's 状态 is its scope, with none. The
 * row under the title bar, 「返回 出库概览」, goes back to the board under
 * 华南.
 */
export const OpenInWorkbench: Story = {
  name: '在工作台中打开：带着板上的筛选',
  args: { variant: 'handed' },
};

/** A dashboard with nothing on it yet. */
export const EmptyDashboard: Story = { args: { variant: 'empty' } };

/**
 * 地址里的仪表盘已经不在了：工作区说「无法打开这个仪表盘」「这个仪表盘已不存在。」
 * 与「回到默认仪表盘」——引擎报的是各种视图共用的代码，这一页说的是仪表盘
 * （D26 Q34）。
 */
export const CannotOpen: Story = { args: { variant: 'deleted' } };

/**
 * 一块在栅格还是 12 列时存下的仪表盘（没有 `columns`）：打开时按 24 列读，
 * `x`、`w` 乘 2，每个面板落在原来的像素上（D22 E）。不变脏，存回才写新格式。
 */
export const LegacyLayout: Story = {
  name: '旧的 12 列布局',
  args: { variant: 'legacy' },
};
