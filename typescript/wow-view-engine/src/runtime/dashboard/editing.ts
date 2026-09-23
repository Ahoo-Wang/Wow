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

import type {
  DashboardViewConfig,
  PanelPresentation,
  ViewConfig,
  ViewInstance,
} from '../../model/index.js';
import { isPlainObject } from '../../filter/index.js';
import {
  addPanel,
  addTab,
  duplicatePanel,
  editContent,
  movePanelToTab,
  moveTab,
  referToSaved,
  removePanel,
  removeTab,
  renamePanel,
  renameTab,
  replacePanelView,
  setPresentation,
  type NewContentPanel,
  type NewPanel,
  type NewPanelPlacement,
} from '../../dashboard/index.js';

/**
 * Building the board (D22 A–E, batch B1). Every edit goes into the draft and
 * onto the screen at once — the panels re-run on it as the author works —
 * and nothing is written until the board is saved; `revert` puts back what
 * was. A global filter being composed meanwhile stays pending, as it does
 * under a `place`. An edit that names a panel or a tab the board lacks does
 * nothing.
 */
export interface DashboardEditing {
  /**
   * Adds a panel — a saved view, a view the board owns, or content — at the
   * first free place on its tab at or below `placement.fromRow`, and returns
   * its id; `null` when the board holds as many panels as it may.
   */
  addPanel(panel: NewPanel, placement?: NewPanelPlacement): string | null;
  /** Removes a panel; its tab closes up behind it. */
  removePanel(panelId: string): void;
  /** Copies a panel beside it (or under it) and returns the copy's id. */
  duplicatePanel(panelId: string): string | null;
  /** Sets a panel's title; a blank one names it by what it shows again. */
  renamePanel(panelId: string, title: string): void;
  /** Points a data panel at another saved view; its override goes. */
  replacePanelView(panelId: string, instanceId: string): void;
  /** Changes what a content panel holds. */
  editPanelContent(panelId: string, patch: Partial<NewContentPanel>): void;
  /** Moves a panel to another tab, at the first free place there. */
  movePanelToTab(panelId: string, tabId: string): void;
  /** Overrides how a data panel looks; `null` puts back the view's own look. */
  setPresentation(
    panelId: string,
    presentation: PanelPresentation | null,
  ): void;
  /**
   * Points a panel that owns its view at the saved view it was saved as
   * (`ViewEngine.saveOwnedView`); the override stays.
   */
  referToSaved(panelId: string, instance: ViewInstance): void;
  /**
   * Adds a tab, last, and returns its id; on a board without tabs the panels
   * go onto a first tab called `firstTitle`. `null` for a blank title or a
   * board that holds as many tabs as it may.
   */
  addTab(title: string, firstTitle: string): string | null;
  renameTab(tabId: string, title: string): void;
  /** Moves a tab to `index` in the bar. */
  moveTab(tabId: string, index: number): void;
  /** Removes a tab and every panel on it; never the board's last tab. */
  removeTab(tabId: string): void;
}

/** What the edits need of the runtime that holds the board. */
export interface EditingHost {
  /** The draft an edit works its ids and places out on; `null` once disposed. */
  draft(): DashboardViewConfig | null;
  /** The most panels a board may hold. */
  readonly maxPanels: number;
  /** The config of a saved view already loaded, for the size a panel starts at. */
  viewConfig(instanceId: string): ViewConfig | undefined;
  /** A reference known without loading: the view an owned one was saved as. */
  seed(instance: ViewInstance): void;
  /**
   * One edit, applied to the draft and to what is on screen alike, and
   * nothing else of either moved (`DashboardViewRuntime.restructure`).
   */
  restructure(
    change: (config: DashboardViewConfig) => DashboardViewConfig,
  ): void;
}

/**
 * The board's edits over one runtime: each a kernel function
 * (`src/dashboard/edit.ts`, `tabs.ts`) handed to `restructure`. An edit that
 * adds works its id and place out once, on the draft, and the screen gets
 * that very panel or tab rather than working both out again.
 */
export function boardEditing(host: EditingHost): DashboardEditing {
  const edit = (change: (config: DashboardViewConfig) => DashboardViewConfig) =>
    host.restructure(change);
  /** An edit that added one panel to the draft, carried onto the screen. */
  const added = (
    from: DashboardViewConfig,
    result: { config: DashboardViewConfig; id: string } | null,
  ): string | null => {
    if (!result) return null;
    const panel = result.config.panels.find(
      entry => isPlainObject(entry) && entry.id === result.id,
    );
    edit(config =>
      config === from
        ? result.config
        : panel
          ? { ...config, panels: [...config.panels, panel] }
          : config,
    );
    return result.id;
  };

  return {
    addPanel(panel, placement = {}) {
      const draft = host.draft();
      if (!draft) return null;
      const instanceId = 'instanceId' in panel ? panel.instanceId : undefined;
      return added(
        draft,
        addPanel(draft, panel, {
          max: host.maxPanels,
          view: instanceId === undefined ? null : host.viewConfig(instanceId),
          ...placement,
        }),
      );
    },
    removePanel: panelId => edit(config => removePanel(config, panelId)),
    duplicatePanel(panelId) {
      const draft = host.draft();
      return draft
        ? added(draft, duplicatePanel(draft, panelId, host.maxPanels))
        : null;
    },
    renamePanel: (panelId, title) =>
      edit(config => renamePanel(config, panelId, title)),
    replacePanelView: (panelId, instanceId) =>
      edit(config => replacePanelView(config, panelId, instanceId)),
    editPanelContent: (panelId, patch) =>
      edit(config => editContent(config, panelId, patch)),
    movePanelToTab: (panelId, tabId) =>
      edit(config => movePanelToTab(config, panelId, tabId)),
    setPresentation: (panelId, presentation) =>
      edit(config => setPresentation(config, panelId, presentation)),
    referToSaved(panelId, instance) {
      if (!host.draft()) return;
      host.seed(instance);
      edit(config => referToSaved(config, panelId, instance.id));
    },
    addTab(title, firstTitle) {
      const draft = host.draft();
      const result = draft && addTab(draft, title, firstTitle);
      if (!draft || !result) return null;
      edit(config =>
        config === draft
          ? result.config
          : (addTab(config, title, firstTitle)?.config ?? config),
      );
      return result.id;
    },
    renameTab: (tabId, title) =>
      edit(config => renameTab(config, tabId, title)),
    moveTab: (tabId, index) => edit(config => moveTab(config, tabId, index)),
    removeTab: tabId => edit(config => removeTab(config, tabId)),
  };
}
