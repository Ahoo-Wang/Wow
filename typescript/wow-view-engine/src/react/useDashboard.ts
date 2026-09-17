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

import { useCallback, useMemo } from 'react';
import {
  DASHBOARD_GRID_COLUMNS,
  type DashboardPanel,
  type Issue,
  type PanelLayout,
} from '../model/index.js';
import type {
  DashboardPanelState,
  DashboardRuntime,
  DataViewRuntime,
} from '../runtime/index.js';
import { useViewRuntime } from './useViewEngine.js';

/** One panel, ready to place: geometry, what runs inside, what is wrong. */
export interface DashboardPanelView {
  id: string;
  title: string | undefined;
  panel: DashboardPanel;
  layout: PanelLayout;
  /** The runtime of a data panel that can run; `null` otherwise. */
  runtime: DataViewRuntime | null;
  issues: Issue[];
  /** True when this panel alone cannot show anything. */
  broken: boolean;
}

/** A new position and size for one panel, as a grid reports it. */
export interface PanelPlacement extends PanelLayout {
  id: string;
}

export interface DashboardController {
  panels: DashboardPanelView[];
  /** Columns a layout is placed in; the kernel admits against the same number. */
  columns: number;
  /** Issues about the dashboard as a whole, panels excluded. */
  issues: Issue[];
  /** True while a panel reference is still loading. */
  resolving: boolean;
  dirty: boolean;
  /** Moves and resizes panels: an edit and an apply, like sorting a table. */
  place(placements: readonly PanelPlacement[]): void;
  /** Re-runs every panel at once. */
  refresh(): void;
}

const EMPTY_PANELS: DashboardPanelState[] = [];

/**
 * A dashboard as a grid of panels.
 *
 * It reports the applied panels rather than the draft's, because a panel is
 * a running query and not a form field: what the grid shows is what is
 * executing. Geometry is the exception the design allows — moving a panel
 * edits and applies in one gesture, so a drag lands immediately.
 */
export function useDashboard(
  runtime: DashboardRuntime | null,
): DashboardController {
  const state = useViewRuntime(runtime);
  const panels = state?.panels ?? EMPTY_PANELS;

  const place = useCallback(
    (placements: readonly PanelPlacement[]) => {
      if (!runtime) return;
      const byId = new Map(placements.map(entry => [entry.id, entry]));
      const current = runtime.getSnapshot().draft.panels;
      const next = current.map(panel => {
        const placement = byId.get(panel.id);
        if (!placement || sameLayout(panel.layout, placement)) return panel;
        const { x, y, w, h } = placement;
        return { ...panel, layout: { x, y, w, h } };
      });
      if (next.every((panel, index) => panel === current[index])) return;
      runtime.edit({ panels: next });
      runtime.apply();
    },
    [runtime],
  );

  return {
    panels: useMemo(() => panels.map(toView), [panels]),
    columns: DASHBOARD_GRID_COLUMNS,
    // A panel's own issues travel with the panel; what is left belongs here.
    issues: (state?.issues ?? []).filter(found => found.path[0] !== 'panels'),
    resolving: state?.resolving ?? false,
    dirty: state?.dirty ?? false,
    place,
    refresh: useCallback(() => runtime?.refresh(), [runtime]),
  };
}

function toView(panel: DashboardPanelState): DashboardPanelView {
  return {
    id: panel.id,
    title: panel.panel.title,
    panel: panel.panel,
    layout: panel.panel.layout,
    runtime: panel.runtime,
    issues: panel.issues,
    // A data panel with no runtime cannot query, and any panel carrying an
    // error was refused by `validateDashboard` — a content panel included,
    // whose markdown, image or link the grid would otherwise render as
    // though nothing were wrong with it.
    broken:
      (panel.runtime === null && panel.panel.kind === 'view') ||
      panel.issues.some(found => found.severity === 'error'),
  };
}

function sameLayout(layout: PanelLayout, next: PanelLayout): boolean {
  return (
    layout.x === next.x &&
    layout.y === next.y &&
    layout.w === next.w &&
    layout.h === next.h
  );
}
