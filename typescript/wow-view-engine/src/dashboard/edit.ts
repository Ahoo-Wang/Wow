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
 * What building a board does to its config (D22 A–E): add a panel, remove,
 * duplicate, rename it, replace the view it shows, move it to a tab, and
 * override or reset how it looks. Each is a pure function from the config
 * to the next one, so the runtime applies one edit to its draft and to what
 * is on screen alike, and a test reads each rule off its function.
 *
 * An edit that names nothing there — a panel id the board lacks, a tab it
 * has not — hands the same config back rather than throwing: the gesture
 * that asked for it raced a change, and there is nothing to do.
 */

import {
  DASHBOARD_GRID_COLUMNS,
  DASHBOARD_WIDTHS,
  DEFAULT_RUNTIME_LIMITS,
  type DashboardWidth,
  type DashboardContentPanel,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type OwnedView,
  type PanelBinding,
  type PanelPresentation,
  type ViewConfig,
  overlaid,
  without,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import {
  compactLayout,
  fitsGrid,
  freeSpot,
  withLayouts,
  type PlacedPanel,
} from './layout.js';
import { freshId, isViewPanel, panelTab } from './panels.js';

/** A content panel as it is added: what it holds, and optionally a title. */
export type NewContentPanel = DashboardContentPanel extends infer P
  ? P extends DashboardContentPanel
    ? Omit<P, 'id' | 'layout' | 'tab'>
    : never
  : never;

/** A data panel as it is added: a saved view, or one the board owns. */
export type NewViewPanel = {
  kind: 'view';
  title?: string;
  bindings?: PanelBinding[];
} & ({ instanceId: string } | { owned: OwnedView });

export type NewPanel = NewViewPanel | NewContentPanel;

/** Where a new panel goes; every member has a default. */
export interface NewPanelPlacement {
  /** The tab it goes on; the first when left out or not the board's. */
  tab?: string;
  /** The first row on screen: it lands there or below (D22 A). */
  fromRow?: number;
  /** Its size; `defaultPanelSize` when left out. */
  size?: { w: number; h: number };
  /** The config of the saved view it shows, when known, for its size. */
  view?: ViewConfig | null;
  /** The most panels the board may hold; admission's limit by default. */
  max?: number;
}

/**
 * How big a new panel starts, in cells of the 24-column grid (D22 A): a
 * metric card a quarter, a chart half, a table the full width; a heading a
 * full-width line, a note or a picture beside a chart. `view` is the config
 * the panel shows, when it is known — an owned view's own, a saved one's
 * once loaded; without it a data panel starts as a chart.
 */
export function defaultPanelSize(
  panel: Pick<NewPanel, 'kind'> & { owned?: OwnedView },
  view?: ViewConfig | null,
): { w: number; h: number } {
  switch (panel.kind) {
    case 'heading':
      return { w: 24, h: 1 };
    case 'markdown':
      return { w: 12, h: 3 };
    case 'image':
      return { w: 8, h: 4 };
    case 'links':
      return { w: 8, h: 3 };
  }
  const config = panel.owned?.config ?? view;
  if (config?.kind === 'record') return { w: 24, h: 5 };
  if (config?.kind === 'analysis') {
    if (config.layout === 'table') return { w: 24, h: 4 };
    if (config.chart?.type === 'metric') return { w: 6, h: 2 };
  }
  return { w: 12, h: 4 };
}

/**
 * The board with one more panel and its id, or `null` when it holds as many
 * as it may. The panel goes on the tab asked for, at the first free place at
 * or below the row on screen (`freeSpot`), under the first id `panel-n` not
 * taken.
 */
export function addPanel(
  config: DashboardViewConfig,
  panel: NewPanel,
  placement: NewPanelPlacement = {},
): { config: DashboardViewConfig; id: string } | null {
  const max = placement.max ?? DEFAULT_RUNTIME_LIMITS.maxDashboardPanels;
  if (config.panels.length >= max) return null;
  const tab = tabOf(config, placement.tab);
  const size = placement.size ?? defaultPanelSize(panel, placement.view);
  const layout = freeSpot(
    boxesOn(config, tab),
    size,
    DASHBOARD_GRID_COLUMNS,
    placement.fromRow ?? 0,
  );
  const id = freshId(
    config.panels.map(entry => (isPlainObject(entry) ? entry.id : undefined)),
    'panel',
  );
  const added = {
    ...(panel.kind === 'view' ? { bindings: [] } : {}),
    ...panel,
    id,
    layout,
    ...(tab === null ? {} : { tab }),
  } as DashboardPanel;
  return { config: { ...config, panels: [...config.panels, added] }, id };
}

/** The board without one panel, its tab compacted where the panel stood. */
export function removePanel(
  config: DashboardViewConfig,
  id: string,
): DashboardViewConfig {
  const panel = find(config, id);
  if (!panel) return config;
  const tab = panelTab(config, panel);
  const rest = {
    ...config,
    panels: config.panels.filter(entry => entry !== panel),
  };
  return compactTab(rest, tab);
}

/**
 * The board with a copy of one panel beside it and the copy's id, or `null`
 * when there is no such panel or no room for one more. The copy keeps the
 * title, the view, the wiring and the override, and lands at the first free
 * place from the original's row — next to it when there is room, under it
 * when there is not. An owned view is copied with it: the copy is its own
 * question from then on, as a copied panel is its own panel.
 */
export function duplicatePanel(
  config: DashboardViewConfig,
  id: string,
  max: number = DEFAULT_RUNTIME_LIMITS.maxDashboardPanels,
): { config: DashboardViewConfig; id: string } | null {
  const panel = find(config, id);
  if (!panel || !fitsGrid(panel.layout)) return null;
  const layout = panel.layout;
  const tab = panelTab(config, panel);
  const copy = without(without(without(panel, 'id'), 'layout'), 'tab');
  return addPanel(config, copy as NewPanel, {
    tab: tab ?? undefined,
    fromRow: layout.y,
    size: { w: layout.w, h: layout.h },
    max,
  });
}

/** A panel's title set, or taken off for a blank one so it is named by what it shows. */
export function renamePanel(
  config: DashboardViewConfig,
  id: string,
  title: string,
): DashboardViewConfig {
  const name = title.trim();
  return mapPanel(config, id, panel => {
    if (name === (panel.title ?? '')) return panel;
    return (
      name.length > 0 ? { ...panel, title: name } : without(panel, 'title')
    ) as DashboardPanel;
  });
}

/**
 * A data panel showing another saved view (D22 D, 「替换视图」). The title
 * and the wiring stay the panel's — a replaced view is usually the same
 * data asked better, and a binding the new view cannot carry is reported by
 * admission. The override goes: it said how to look at the view replaced.
 * An owned view is let go with it.
 */
export function replacePanelView(
  config: DashboardViewConfig,
  id: string,
  instanceId: string,
): DashboardViewConfig {
  return mapViewPanel(config, id, panel => {
    if (panel.instanceId === instanceId && panel.presentation === undefined)
      return panel;
    const rest = without(without(panel, 'owned'), 'presentation');
    return { ...rest, instanceId };
  });
}

/**
 * A data panel pointing at a saved view that is the very view it showed:
 * its own analysis just saved as a view (「另存为视图」, D22 C), or the
 * personal view it stood on just copied for the board's audience
 * (「复制为共享视图并替换」, D22 B). Unlike a replacement it keeps the
 * override, the click and the wiring: the panel shows the same thing, and
 * should look and answer the same.
 */
export function referToSaved(
  config: DashboardViewConfig,
  id: string,
  instanceId: string,
): DashboardViewConfig {
  return mapViewPanel(config, id, panel => {
    if (panel.owned === undefined && panel.instanceId === instanceId)
      return panel;
    return { ...without(panel, 'owned'), instanceId };
  });
}

/**
 * A data panel's override of how it looks (D22 D), or its override taken
 * off (`null`, or one setting nothing): 「恢复为视图的样子」.
 */
export function setPresentation(
  config: DashboardViewConfig,
  id: string,
  presentation: PanelPresentation | null,
): DashboardViewConfig {
  const next =
    presentation && Object.keys(presentation).length > 0 ? presentation : null;
  return mapViewPanel(config, id, panel => {
    if (next === null && panel.presentation === undefined) return panel;
    return (
      next === null
        ? without(panel, 'presentation')
        : { ...panel, presentation: next }
    ) as DashboardViewPanel;
  });
}

/**
 * A content panel with what it holds changed — a note's text, a picture, a
 * list of links. Its kind stays: a note does not become a picture by an
 * edit, and a data panel is not a content panel to edit this way. A member
 * the patch sets to `undefined` is taken off, and a `title` is read as
 * `renamePanel` reads one — blank takes it off — so the form a panel is
 * written in is one edit, title and all.
 */
export function editContent(
  config: DashboardViewConfig,
  id: string,
  patch: Partial<NewContentPanel>,
): DashboardViewConfig {
  const edited = mapPanel(config, id, panel => {
    if (
      panel.kind === 'view' ||
      (patch.kind !== undefined && patch.kind !== panel.kind)
    )
      return panel;
    // The title is `renamePanel`'s to read, below.
    return overlaid(panel, 'title' in patch ? without(patch, 'title') : patch);
  });
  const panel = find(edited, id);
  return 'title' in patch && panel && panel.kind !== 'view'
    ? renamePanel(edited, id, patch.title ?? '')
    : edited;
}

/**
 * A panel moved onto another tab, at the first free place there, and the
 * tab it left compacted. The same board for a tab it lacks or the tab the
 * panel is already on.
 */
export function movePanelToTab(
  config: DashboardViewConfig,
  id: string,
  tab: string,
): DashboardViewConfig {
  const panel = find(config, id);
  if (!panel || !config.tabs.some(entry => entry.id === tab)) return config;
  const from = panelTab(config, panel);
  if (from === tab || !fitsGrid(panel.layout)) return config;
  const layout = freeSpot(boxesOn(config, tab), panel.layout);
  const moved = mapPanel(config, id, entry => ({ ...entry, tab, layout }));
  return compactTab(moved, from);
}

/** The panels of one tab floated up, holes and all gone. */
export function compactTab(
  config: DashboardViewConfig,
  tab: string | null,
): DashboardViewConfig {
  return withLayouts(config, compactLayout(boxesOn(config, tab)));
}

/**
 * The board laid out at another width (D31). It is written even when it is
 * `full` — a board the author chose full for says so, rather than looking
 * like one saved before a board could say — and the same config comes back
 * when the board is already read at that width and says it.
 */
export function setBoardWidth(
  config: DashboardViewConfig,
  width: DashboardWidth,
): DashboardViewConfig {
  if (!DASHBOARD_WIDTHS.includes(width)) return config;
  return config.width === width ? config : { ...config, width };
}

/** The boxes of the panels on one tab (`null`: a board without tabs). */
function boxesOn(
  config: DashboardViewConfig,
  tab: string | null,
): PlacedPanel[] {
  return config.panels.flatMap(panel =>
    isPlainObject(panel) &&
    typeof panel.id === 'string' &&
    fitsGrid(panel.layout) &&
    panelTab(config, panel) === tab
      ? [{ id: panel.id, ...panel.layout }]
      : [],
  );
}

/** The tab asked for when the board has it, its first otherwise. */
function tabOf(
  config: DashboardViewConfig,
  wanted: string | undefined,
): string | null {
  return panelTab(config, { tab: wanted });
}

function find(
  config: DashboardViewConfig,
  id: string,
): DashboardPanel | undefined {
  return config.panels.find(panel => isPlainObject(panel) && panel.id === id);
}

function mapPanel(
  config: DashboardViewConfig,
  id: string,
  change: (panel: DashboardPanel) => DashboardPanel,
): DashboardViewConfig {
  const panel = find(config, id);
  if (!panel) return config;
  const next = change(panel);
  return next === panel
    ? config
    : {
        ...config,
        panels: config.panels.map(entry => (entry === panel ? next : entry)),
      };
}

function mapViewPanel(
  config: DashboardViewConfig,
  id: string,
  change: (panel: DashboardViewPanel) => DashboardViewPanel,
): DashboardViewConfig {
  return mapPanel(config, id, panel =>
    isViewPanel(panel) ? change(panel) : panel,
  );
}
