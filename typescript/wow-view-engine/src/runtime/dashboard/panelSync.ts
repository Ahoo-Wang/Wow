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

/**
 * Bringing a board's panels in line with what is applied: one child runtime
 * per data panel that can run on the tab shown, none for the rest, each under
 * the board's filters and the current scope (`sync`, `syncPanel`). It keeps
 * what one sync hands the next — the tab the reader asked for, whether the
 * panels were brought in line once, the filters last sent, and the window
 * each anchored trend card ran under (D39) — and reads everything else off
 * the runtime it serves (`PanelSyncHost`).
 */

import { dequal } from 'dequal';
import type {
  DashboardFilters,
  DashboardViewConfig,
  DashboardViewPanel,
  Issue,
  RuntimeLimits,
  ViewScope,
} from '../../model/index.js';
import type { FieldKindRegistry } from '../../filter/index.js';
import {
  referencedInstance,
  type DataPanelSource,
} from '../../dashboard/index.js';
import { hasError } from '../runtimeStore.js';
import type { RuntimeEnvironment } from '../environment.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { PanelAnchor } from './anchor.js';
import type { PanelChildren, PanelView } from './children.js';
import type { DashboardRuntimeState } from './contract.js';
import type { FilterValues } from './filterValues.js';
import { boardPanels, panelRun } from './panelRun.js';
import { panelFailure, samePanels, shownTab } from './panels.js';
import type { PanelReferenceLoads } from './references.js';

/** What a `PanelSync` reads off the runtime it brings in line. */
export interface PanelSyncHost {
  readonly kinds: FieldKindRegistry;
  readonly limits: RuntimeLimits;
  readonly environment: RuntimeEnvironment;
  disposed(): boolean;
  state(): DashboardRuntimeState;
  setState(patch: Partial<DashboardRuntimeState>): void;
  admit(config: DashboardViewConfig, scope: ViewScope): Issue[];
  viewOf(panel: DataPanelSource): PanelView | null;
  values(): FilterValues;
  children(): PanelChildren;
  references(): PanelReferenceLoads;
}

export class PanelSync {
  /** The tab the reader asked for; see `DashboardRuntimeState.tab`. */
  private requestedTab: string | null = null;
  /** Whether the panels were brought in line once: until then a tab is only noted. */
  private synced = false;
  /** The filters the last sync sent the panels; see `filtersRun`. */
  private filtersOut: DashboardFilters | null = null;
  /** The window each anchored trend card ran under at the last sync (D39). */
  private readonly anchors = new Map<string, PanelAnchor>();

  constructor(private readonly host: PanelSyncHost) {}

  /** Whether the panels were brought in line once. */
  get started(): boolean {
    return this.synced;
  }

  /** Whether a trend card ran under an anchored window at the last sync. */
  get anchored(): boolean {
    return this.anchors.size > 0;
  }

  /** The window panel `panelId` ran under at the last sync, if anchored. */
  anchorOf(panelId: string): PanelAnchor | null {
    return this.anchors.get(panelId) ?? null;
  }

  /** Notes the tab the reader asked for; the next sync shows it. */
  request(tabId: string | null): void {
    this.requestedTab = tabId;
  }

  /**
   * Brings the child runtimes in line with the applied panels: one per data
   * panel that can run, none for the rest, and the current scope in each.
   */
  sync(patch: Partial<DashboardRuntimeState> = {}): void {
    if (this.host.disposed()) return;
    this.synced = true;
    const applied = patch.applied ?? this.host.state().applied;
    const issues = this.host.admit(applied, this.host.state().scope);
    // Only the tab on screen runs (D22 E). A panel elsewhere keeps the child
    // it has, rows and all, exactly as it stands — neither re-scoped nor
    // re-run — until its tab is shown; one never shown has none yet.
    const tab = shownTab(applied, this.requestedTab);
    // What the filters hold, read against the board as it now is: a filter
    // taken off holds nothing, a required one never less than its default.
    const filters = this.host.values().on(applied);
    this.host.values().askLabels(applied, filters);
    const panels = boardPanels({
      applied,
      issues,
      tab,
      filters,
      held: name => this.host.values().holds(name),
      viewOf: panel => this.host.viewOf(panel),
      run: (panel, index, own) =>
        this.syncPanel(panel, index, applied, filters, own),
      hold: (panel, index, own) =>
        this.host.children().hold(panel.id, index, own),
    });
    this.host
      .children()
      .keepOnly(
        new Set(panels.flatMap(panel => (panel.runtime ? [panel.id] : []))),
      );

    // A re-sync that changes nothing keeps the previous array, so a grid
    // bound with `useSyncExternalStore` does not re-render on every apply.
    // After the panels: what a filter change started is running by the
    // time the step is seen. A change's own run is a step whatever it sent.
    const filtersRun =
      patch.filtersRun ??
      this.host.state().filtersRun + (dequal(filters, this.filtersOut) ? 0 : 1);
    this.filtersOut = filters;
    this.host.setState({
      ...patch,
      tab,
      filters,
      filtersRun,
      panels: samePanels(this.host.state().panels, panels)
        ? this.host.state().panels
        : panels,
    });
  }

  /**
   * One panel's child, and what the panel reports. A reference that could
   * not be put to work, or a panel with an error of its own, runs nothing;
   * otherwise the child is brought in line with the panel and the scope
   * (`PanelChildren.sync`), and a refusal lands in the panel's issues where
   * the dashboard's own would.
   */
  private syncPanel(
    panel: DashboardViewPanel,
    index: number,
    applied: DashboardViewConfig,
    filters: DashboardFilters,
    own: Issue[],
  ): { runtime: DataViewRuntime | null; issues: Issue[] } {
    const instanceId = referencedInstance(panel) ?? '';
    const failure = this.host.references().failure(instanceId);
    if (failure !== undefined)
      return {
        runtime: null,
        // Said once: a read that failed is the panel's own finding already.
        issues: own.some(found => found.code === 'dashboard.panel.failed')
          ? own
          : [...own, panelFailure(index, instanceId, failure)],
      };

    const view = this.host.viewOf(panel);
    // A panel with a problem of its own does not query; the others still do.
    if (!view || hasError(own)) return { runtime: null, issues: own };

    const run = panelRun(panel, index, view, {
      applied,
      filters,
      kinds: this.host.kinds,
      limits: this.host.limits,
      clock: {
        now: this.host.environment.now(),
        timeZone: this.host.environment.timeZone,
      },
    });
    if (run.anchor) this.anchors.set(panel.id, run.anchor);
    else this.anchors.delete(panel.id);
    return this.host
      .children()
      .sync(panel.id, index, [...own, ...run.issues], run.view, run.scope);
  }
}
