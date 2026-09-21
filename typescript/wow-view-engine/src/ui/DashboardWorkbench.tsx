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

import type { FieldOption, Issue } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import { useDashboard, useWorkbench } from '../react/index.js';
import { DashboardGrid } from './DashboardGrid.js';
import { FilterPanel } from './FilterPanel.js';
import { RefreshControl } from './RefreshControl.js';
import { FilterModes, filterModeLabel } from './filter/FilterModes.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import type { RenderFailureHandler } from './RenderBoundary.js';

export interface DashboardWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /** Opens this view first; the user's effective default when left out. */
  instanceId?: string | null;
  /** Whether panels may be dragged and resized. */
  editable?: boolean;
  theme?: 'light' | 'dark';
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
   * it starts and the shell owns it from there.
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
 * The filter panel here edits the dashboard's own fields rather than a
 * definition's, which is why a runtime reports the fields to edit against
 * instead of the editor reading them off a definition. Submitting it applies
 * the dashboard, and every panel re-runs with the condition mapped onto its
 * own fields.
 */
export function DashboardWorkbench({
  engine,
  definitionId,
  instanceId = null,
  editable = false,
  theme,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  onRenderFailure,
}: DashboardWorkbenchProps) {
  const workbench = useWorkbench(engine, definitionId, {
    kind: 'dashboard',
    instanceId,
  });
  const { filter, runtime, state } = workbench;
  const board = runtime?.kind === 'dashboard' ? runtime : null;
  const dashboard = useDashboard(board);
  const messages = useViewMessages(wording);

  const issues = state?.issues ?? [];
  // The panels carry the warnings of what is applied, each in its own frame.
  // The draft's are not all carried: a global condition mapped onto a panel
  // field that warns, not yet applied, is a finding under `['panels', …]`
  // that no panel holds until Apply hands it over — and Save would persist
  // it unseen. So the notice takes every warning no panel is showing.
  const carried = dashboard.panels.flatMap(panel => panel.issues);
  const warnings = issues.filter(
    found =>
      found.severity === 'warning' &&
      !carried.some(shown => sameIssue(shown, found)),
  );
  // A dashboard runs nothing of its own — `state.result` is always null — so
  // what the applied bar describes is whether the panels were asked at all.
  // One panel that has answered, or that is asking, is an answer: the global
  // condition it went out under is exactly what the bar says.
  // Nothing to filter without global fields, and an empty fold in the title
  // bar is a control that opens on nothing.
  const hasGlobalFilter =
    dashboard.panels.length > 0 && filter.fields.length > 0;
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
      kind="dashboard"
      title={engine.definitions.get(definitionId)?.title}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      defaultSidebarOpen={defaultSidebarOpen}
      onSidebarOpenChange={onSidebarOpenChange}
      expandable={expandable}
      onRenderFailure={onRenderFailure}
      hasResult={hasResult}
      resultSurface={false}
      warnings={warnings}
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
      editorLabel={
        hasGlobalFilter ? messages.label('label.filter.panel') : undefined
      }
      editorModeLabel={
        hasGlobalFilter ? filterModeLabel(filter, messages) : undefined
      }
      editorModes={
        hasGlobalFilter ? <FilterModes filter={filter} /> : undefined
      }
      editorPending={filter.pendingCount}
      editor={
        /* Without global fields there is nothing to filter, and an empty
           panel would only take up room. */
        hasGlobalFilter && (
          <FilterPanel filter={filter} optionsFor={optionsFor} modes={false} />
        )
      }
      result={
        <DashboardGrid
          dashboard={dashboard}
          editable={editable}
          onRenderFailure={onRenderFailure}
        />
      }
    />
  );
}

/**
 * Whether two findings are the same finding. A panel's issues are the
 * dashboard's own re-addressed and the child's rebased, so they never share
 * an object with the draft's; the finding is compared instead.
 */
function sameIssue(a: Issue, b: Issue): boolean {
  if (a.code !== b.code || a.severity !== b.severity) return false;
  if (a.path.length !== b.path.length) return false;
  if (a.path.some((segment, index) => segment !== b.path[index])) return false;
  const left = a.params ?? {};
  const right = b.params ?? {};
  const keys = Object.keys(left);
  return (
    keys.length === Object.keys(right).length &&
    keys.every(key => left[key] === right[key])
  );
}
