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

import { useCallback, useEffect, useRef } from 'react';
import {
  audienceOf,
  type DashboardFilters,
  type DashboardViewConfig,
  type FieldOption,
} from '../model/index.js';
import type { ViewNavigation, ViewEngine } from '../runtime/index.js';
import { useDashboard, useWorkbench } from '../react/index.js';
import { SurfaceAnnouncer, useAnnouncer } from './Announcer.js';
import { DashboardBoard } from './dashboard/Board.js';
import { RefreshControl } from './RefreshControl.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { featuresOf, type WorkbenchFeatures } from './features.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import type { RenderFailureHandler } from './RenderBoundary.js';
import type { ViewTheme } from './ViewSurface.js';
import { DashboardTabs } from './dashboard/DashboardTabs.js';
import { boardFindingNamer } from './dashboard/findings.js';
import { useBuildShell } from './dashboard/buildShell.js';
import { useDashboardExtensions } from './dashboard/building.js';
import {
  DashboardEditExtensionsContext,
  useDashboardEditExtensions,
} from './dashboard/extensions.js';

export interface DashboardWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which view is open, as `value` is on an input: leaving it out lets the
   * workbench own it from the effective default on, and passing it — a
   * string, or `null` for that default — puts a host's route in charge, every
   * later change opening what it names. It goes through the leave guard, so a
   * pushed view never takes an unsaved draft away without asking
   * (`WorkbenchOptions.instanceId`).
   */
  instanceId?: string | null;
  /**
   * Told which view is open whenever that changes, in the same vocabulary
   * `instanceId` is written in — `null` is the effective default — so a host
   * can put it straight into a route and get the same view back from the
   * link.
   */
  onInstanceChange?(id: string | null): void;
  /**
   * The tab the board opens on (D22 E), as a host's route has it: read with
   * `instanceId` — a tab of the board it names, or with `instanceId` left
   * out, of the first board this workbench opens. Left out, or a tab the
   * board does not have, the board opens where its reader last read it.
   */
  initialTab?: string | null;
  /**
   * Told which tab is on screen whenever that changes — the board opening
   * included — so a host can write it into its route; `null` for a board
   * without tabs. The package never touches the address itself.
   */
  onTabChange?(tabId: string | null): void;
  /**
   * What the board's filters hold as it opens (D22 F), as a host's address
   * has them: read with `instanceId` exactly as `initialTab` is. Left out,
   * every filter starts at its default; what the board does not take is
   * left out.
   */
  initialFilters?: DashboardFilters | null;
  /**
   * Told what the filters hold whenever that changes — the board opening
   * included — so a host can write it into its address. Filter values are
   * the reader's and never the board's config (「筛选值写进地址，不写进配置」):
   * the package never touches the address itself.
   */
  onFiltersChange?(filters: DashboardFilters): void;
  /**
   * The host's route (D22 D, H, I): every way off the board goes through it
   * — 在工作台中打开 on a panel (the saved view under the board's filters, in
   * its own field names; or a board's own analysis, unsaved), the follow-up
   * menu on a group (a view nobody saved, the board's filters and the group
   * its conditions), and a panel's custom destination. The package never
   * touches the address. Without it none of these exist: a press on a group
   * does nothing unless the panel cross-filters.
   */
  onNavigate?(to: ViewNavigation): void;
  /**
   * What a new dashboard starts from: an empty one when left out. It opens
   * unsaved, and the first save asks for its name and audience.
   */
  template?: DashboardViewConfig;
  /** The mode, as `ViewSurface` takes it: follows the host when left out. */
  theme?: ViewTheme;
  /** A preset pinned on the surface and its popups (`ViewSurface`). */
  preset?: string;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * The sidebar this workbench opens on. It is view state and nothing else —
   * never saved, never asked about by the leave guard — so a host sets where
   * it starts and the shell owns it from there. Left out, a column narrower
   * than `md` opens folded and a wider one opens with the list beside it.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
  /**
   * Whether this workbench offers to fill the screen. On by default, and the
   * fold itself belongs to the shell — a host only says whether the control
   * exists, because a page that is already one full-screen view of one thing
   * has nothing to gain from a second way to say so.
   */
  expandable?: boolean;
  /**
   * Which of the workbench's own controls are on screen (D18 XI): `manage`
   * — the view manager's gear and switcher item — and `export`, a record
   * panel's 导出数据… (the others name record-view controls). A board
   * whose data must not leave the page turns `export` off, as the record
   * workbench's host does.
   */
  features?: Pick<WorkbenchFeatures, 'manage' | 'export'>;
  /**
   * Told of a render failure one of the workbench's boundaries caught — the
   * host's action slots, the editor, the result, a panel. The part shows a
   * recoverable error state in place regardless; this is the host's copy.
   */
  onRenderFailure?: RenderFailureHandler;
}

/**
 * The default Dashboard workbench: the view list, the global filter, the
 * panels and the save commands.
 *
 * The board's filters are a bar of their own over the panels (D22 F,
 * `FilterBar`): each value runs on its own a moment after it changes, and
 * reaches the panels wired to it through their own fields.
 */
/** The one kind a dashboard workbench draws, held once so the list is not re-narrowed per render. */
const DASHBOARD = ['dashboard'] as const;

export function DashboardWorkbench({
  engine,
  definitionId,
  instanceId,
  onInstanceChange,
  onNavigate,
  theme,
  preset,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  onRenderFailure,
  template,
  features,
  initialTab,
  onTabChange,
  initialFilters,
  onFiltersChange,
}: DashboardWorkbenchProps) {
  const messages = useViewMessages(wording, locale);
  // The host's tab and filters, asked as a board opens: of the board its
  // `instanceId` names, or — left uncontrolled — of the first board opened.
  const hostOpening = useRef({ instanceId, initialTab, initialFilters });
  useEffect(() => {
    hostOpening.current = { instanceId, initialTab, initialFilters };
  }, [instanceId, initialTab, initialFilters]);
  // Whether a board has opened here yet: an uncontrolled workbench hands the
  // host's tab to that first one only — the next board the reader picks is
  // one the address never named.
  const openedOnce = useRef(false);
  const opening = useCallback((id: string) => {
    const {
      instanceId: named,
      initialTab: tab,
      initialFilters: filters,
    } = hostOpening.current;
    const ours = named == null ? !openedOnce.current : named === id;
    if (!ours) return undefined;
    return {
      ...(tab == null ? {} : { tab }),
      ...(filters == null ? {} : { filters }),
    };
  }, []);
  const workbench = useWorkbench(engine, definitionId, {
    kinds: DASHBOARD,
    instanceId,
    onInstanceChange,
    opening,
    newView: {
      title: messages.label('label.dashboard.new-title'),
      ...(template ? { templates: { dashboard: template } } : {}),
    },
  });
  const { filter, runtime, state } = workbench;
  const board = runtime?.kind === 'dashboard' ? runtime : null;
  useEffect(() => {
    if (board) openedOnce.current = true;
  }, [board]);
  const dashboard = useDashboard(board);

  const savedId = state?.saved?.id;
  // The tab on screen is remembered as the reader's own when they pick one
  // (a preference, never the board's).
  const rememberTab = (tabId: string) => {
    if (savedId) void engine.rememberTab(definitionId, savedId, tabId);
  };

  // Reading and building are two states (D22 A): nothing on a board being
  // read moves, and 「编辑」 is the way in — only for whoever may save the
  // board, so a system board (read-only, D4) offers 「另存为」 and nothing
  // else. The state is the runtime's, so this opening's — another view
  // opens read — and the board's timer waits on it (D26 Q39).
  const editing = board !== null && dashboard.building;
  const canEdit = board !== null && workbench.commands.can.save;
  const setEditing = dashboard.setBuilding;
  // 「编辑」, the keyboard going back to it, and the host told of the tab
  // and the filters. The leave guard is `useWorkbench`'s, over the whole
  // draft.
  const { editButton } = useBuildShell({
    dashboard,
    messages,
    canEdit,
    editing,
    tab: board ? dashboard.tab : undefined,
    onTabChange,
    filters: board ? dashboard.filters : undefined,
    onFiltersChange,
  });

  // What building a board adds beyond the edit bar and the panel menu (D22
  // C–E), as the one extension contract the board reads: the new-analysis
  // dialog, a panel's own look and its reset, promoting an owned analysis,
  // and the tab bar — which arranges tabs while the board is built, the one
  // edit state above.
  // A host that provides an entry of its own around the workbench replaces
  // that one entry; the rest are the workbench's.
  const hosted = useDashboardEditExtensions();
  // The board's one live region: the grid, the tabs, the filters, the
  // building and its dialogs all say what they did in it (Q-03).
  const voice = useAnnouncer('dashboard-announcement');
  const { extensions: own, dialogs } = useDashboardExtensions({
    engine,
    board,
    dashboard,
    messages,
    optionsFor,
    say: voice.say,
    tabBar: board && (
      <DashboardTabs
        dashboard={dashboard}
        editing={editing ? board : null}
        onShow={rememberTab}
      />
    ),
  });

  // What the board says above its panels, read once for every surface
  // that draws one (`DashboardController.issues`); its warnings are the
  // status line's, a panel's said after the panel and a filter by its name
  // on the bar (`boardFindingNamer`).
  const warnings = dashboard.issues.filter(
    found => found.severity === 'warning',
  );
  const nameIssue = boardFindingNamer(dashboard, state?.draft, messages);
  // A dashboard runs nothing of its own — `state.result` is always null — so
  // whether there is a result is whether the panels were asked at all: one
  // that has answered, or that is asking, is an answer.
  const hasResult = dashboard.panels.some(panel => {
    const panelState = panel.runtime?.getSnapshot();
    return (
      panelState !== undefined &&
      (panelState.result !== null || panelState.query.status !== 'idle')
    );
  });

  return (
    <WorkbenchShell
      workbench={workbench}
      title={engine.definitions.get(definitionId)?.title}
      theme={theme}
      preset={preset}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      defaultSidebarOpen={defaultSidebarOpen}
      onSidebarOpenChange={onSidebarOpenChange}
      expandable={expandable}
      manage={featuresOf(features).manage}
      build={editButton}
      commitElsewhere={editing}
      onRenderFailure={onRenderFailure}
      // A grid of cards is not framed again.
      resultFramed={false}
      hasResult={hasResult}
      warnings={warnings}
      nameIssue={nameIssue}
      // The panels fail one by one and say so each in its own card; the
      // dashboard's own query has nothing to report in a strip.
      strips={null}
      // The dashboard's own interval, which is the only one that runs here:
      // `DashboardRuntime` holds one timer for the whole board and ignores
      // what a referenced view saved for itself, so the menu says which one
      // it is setting rather than letting the control imply it reaches into
      // the panels (`docs/design/runtime.md`, "Dashboard").
      freshness={
        <RefreshControl
          refresh={workbench.refresh}
          variant="outline"
          // A dashboard runs no query of its own, so "something is out"
          // is the panels' answer: while any of them is querying, pressing
          // refresh would only replace requests that are already on their
          // way, and the spinner is the only sign the user gets that they
          // were.
          busy={dashboard.resolving || dashboard.loading}
          note={messages.label('label.refresh.panels')}
        />
      }
      // No 「正在显示」 band on a board (D27): its filters run as they
      // change, so the filter bar is what the panels are showing — and the
      // board's fixed scope is read on that bar's row.
      applied={false}
      result={
        state && (
          <DashboardEditExtensionsContext.Provider
            value={{ ...own, ...hosted }}
          >
            <SurfaceAnnouncer say={voice.say}>
              <DashboardBoard
                engine={engine}
                dashboard={dashboard}
                commands={workbench.commands}
                title={state.title}
                shared={audienceOf(state.scope) === 'shared'}
                canEdit={canEdit}
                editing={editing}
                onEditingChange={setEditing}
                onSaved={workbench.onSaved}
                onNavigate={onNavigate}
                onRenderFailure={onRenderFailure}
                refusedFilters={board?.refusedFilters}
                fixed={filter.fixed}
                panelExport={featuresOf(features).export}
              />
              {dialogs}
            </SurfaceAnnouncer>
            {voice.region}
          </DashboardEditExtensionsContext.Provider>
        )
      }
    />
  );
}
