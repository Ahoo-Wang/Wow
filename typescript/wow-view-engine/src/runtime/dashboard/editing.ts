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
  DashboardFilterType,
  DashboardTimeGrouping,
  DashboardViewConfig,
  DashboardViewPanel,
  FieldOption,
  FilterValue,
  PanelClick,
  PanelLayout,
  PanelPresentation,
  ViewConfig,
  ViewInstance,
} from '../../model/index.js';
import { isPlainObject } from '../../filter/index.js';
import {
  addFilter,
  addPanel,
  filtersOf,
  addTab,
  autoBindings,
  bindPanel,
  moveFilter,
  removeFilter,
  renameFilter,
  retypeFilter,
  setFilterDefault,
  setFilterMultiple,
  setFilterOptions,
  setFilterRequired,
  setTimeGrouping,
  unbindPanels,
  type NewFilter,
  type PanelFields,
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
  setPanelClick,
  setPresentation,
  placePanelIn,
  reorderPanelIn,
  type OrderStep,
  type NewContentPanel,
  type NewPanel,
  type NewPanelPlacement,
} from '../../dashboard/index.js';
import {
  EditHistory,
  rewound,
  type EditCommand,
  type EditHistoryState,
  type EditStep,
} from './history.js';

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
   * Points a data panel at a saved view that is the very view it showed —
   * its own analysis saved as a view (`ViewEngine.saveOwnedView`), or the
   * personal view it stood on copied for the board (`copyPanelView`); the
   * override, the click and the wiring stay.
   */
  referToSaved(panelId: string, instance: ViewInstance): void;
  /**
   * What a press on one group of a data panel does (D22 I, 「点击时…」),
   * or `null` for the follow-up menu.
   */
  setPanelClick(panelId: string, click: PanelClick | null): void;
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
  /**
   * Puts one panel at `layout` and applies that alone: the panels it now
   * covers make way and its tab floats up behind it (`placePanel`), and
   * every other pending edit — a global filter not yet applied, say — stays
   * pending. A layout the grid does not admit, or a panel id there is none
   * of, is ignored. No panel re-queries for it: a child is kept while its
   * reference and its scope are unchanged, and neither is.
   */
  place(panelId: string, layout: PanelLayout): void;
  /**
   * Moves a panel one place along its tab's reading order — the one-column
   * reading's 「上移」／「下移」 (D22 J) — written back onto the grid as the
   * layout that reads that way and disturbs the rest least
   * (`reorderPanel`). Nothing at either end.
   */
  reorderPanel(panelId: string, step: OrderStep): void;
  /**
   * Takes the last edit back — the members of the board it changed, on the
   * draft and on screen alike — and returns the step taken back; `null`
   * when there is none. Every command here and in `DashboardFilterEditing`
   * is one step; a burst of one naming or setting on one thing is one.
   */
  undo(): EditStep | null;
  /** Makes the last step taken back again, until the next edit; `null` when there is none. */
  redo(): EditStep | null;
}

/**
 * Setting up the board's filters (D22 G), through the draft like every
 * other edit of the board: each goes into the draft and onto the screen at
 * once, and is saved with the board.
 */
export interface DashboardFilterEditing {
  /** Adds a filter, last on the bar, and returns its name; `null` when full. */
  addFilter(filter: NewFilter): string | null;
  /** A filter's name on the bar; a blank one is not taken. */
  renameFilter(name: string, label: string): void;
  /** Gives a filter another type; its default, list and wires go. */
  retypeFilter(name: string, type: DashboardFilterType): void;
  /** Removes a filter and every wire to it. */
  removeFilter(name: string): void;
  /** What a filter starts at — and holds now; `null` takes it off. */
  setFilterDefault(name: string, value: FilterValue | null): void;
  setFilterRequired(name: string, required: boolean): void;
  setFilterMultiple(name: string, multiple: boolean): void;
  /** A list of its own to pick from, or `null`: the wired fields' values. */
  setFilterOptions(name: string, options: FieldOption[] | null): void;
  /** Moves a filter to `index` on the bar. */
  moveFilter(name: string, index: number): void;
  /**
   * Wires one panel to a filter through `panelField` by hand, and every
   * other panel with a field of that name and type on its own; returns the
   * panels auto-connect wired (`bindPanel`).
   */
  bindPanel(name: string, panelId: string, panelField: string): string[];
  /** Takes these panels' wires to a filter off — an undo of auto-connect too. */
  unbindPanels(name: string, panelIds: readonly string[]): void;
  /** Sets the board's time grouping, or takes it off with `null`. */
  setTimeGrouping(grouping: DashboardTimeGrouping | null): void;
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
   * The fields of the view a data panel shows, once known — what wiring and
   * auto-connect match a filter against.
   */
  fieldsOf: PanelFields;
  /** What is on screen, which every edit changes with the draft; `null` once disposed. */
  applied(): DashboardViewConfig | null;
  /**
   * The draft and the screen as one edit (or one step taken back) left
   * them, nothing else of either moved, and the history after it
   * (`DashboardViewRuntime.commit`).
   */
  commit(
    draft: DashboardViewConfig,
    applied: DashboardViewConfig,
    history: EditHistoryState,
  ): void;
}

/** The board's edits, and the history they keep. */
export interface BoardEdits extends DashboardEditing, DashboardFilterEditing {
  /** Starts the history again — a revert, a save, a board read anew — and says it is empty. */
  forget(): EditHistoryState;
  /** What a filter starts at in the draft now; `null` for none. */
  defaultOf(name: string): FilterValue | null;
}

/**
 * The board's edits over one runtime: each a kernel function
 * (`src/dashboard/edit.ts`, `tabs.ts`) handed to `restructure`. An edit that
 * adds works its id and place out once, on the draft, and the screen gets
 * that very panel or tab rather than working both out again.
 */
export function boardEditing(host: EditingHost): BoardEdits {
  const history = new EditHistory();
  /** One edit, on the draft and the screen alike, noted as one step of `command` about `subject`. */
  const edit = (
    command: EditCommand,
    subject: string | null,
    change: (config: DashboardViewConfig) => DashboardViewConfig,
  ) => {
    const draft = host.draft();
    const applied = host.applied();
    if (!draft || !applied) return;
    const next = [change(draft), change(applied)] as const;
    if (next[0] === draft && next[1] === applied) return;
    history.record({ command, subject }, [draft, next[0]], [applied, next[1]]);
    host.commit(...next, history.state);
  };
  /**
   * One step taken back or made again: the members it changed laid over the
   * draft and the screen, the same way an edit goes, and nothing else moved.
   */
  const rewind = (way: 'undo' | 'redo'): EditStep | null => {
    const draft = host.draft();
    const applied = host.applied();
    if (!draft || !applied) return null;
    const step = way === 'undo' ? history.undo() : history.redo();
    if (!step) return null;
    host.commit(
      rewound(draft, step.draft),
      rewound(applied, step.applied),
      history.state,
    );
    return step.step;
  };
  /** An edit that added one panel to the draft, carried onto the screen. */
  const added = (
    command: EditCommand,
    from: DashboardViewConfig,
    result: { config: DashboardViewConfig; id: string } | null,
  ): string | null => {
    if (!result) return null;
    const panel = result.config.panels.find(
      entry => isPlainObject(entry) && entry.id === result.id,
    );
    edit(command, result.id, config =>
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
      // A data panel comes onto the board wired to every filter it has a
      // field for, the way auto-connect wires the rest (D22 G).
      const fields =
        panel.kind === 'view'
          ? host.fieldsOf(panel as unknown as DashboardViewPanel)
          : null;
      const wired =
        panel.kind === 'view' && fields
          ? {
              ...panel,
              bindings: [
                ...(panel.bindings ?? []),
                ...autoBindings(draft, fields, panel.bindings),
              ],
            }
          : panel;
      return added(
        'addPanel',
        draft,
        addPanel(draft, wired, {
          max: host.maxPanels,
          view: instanceId === undefined ? null : host.viewConfig(instanceId),
          ...placement,
        }),
      );
    },
    removePanel: panelId =>
      edit('removePanel', panelId, config => removePanel(config, panelId)),
    duplicatePanel(panelId) {
      const draft = host.draft();
      return draft
        ? added(
            'duplicatePanel',
            draft,
            duplicatePanel(draft, panelId, host.maxPanels),
          )
        : null;
    },
    renamePanel: (panelId, title) =>
      edit('renamePanel', panelId, config =>
        renamePanel(config, panelId, title),
      ),
    replacePanelView: (panelId, instanceId) =>
      edit('replacePanelView', panelId, config =>
        replacePanelView(config, panelId, instanceId),
      ),
    editPanelContent: (panelId, patch) =>
      edit('editPanelContent', panelId, config =>
        editContent(config, panelId, patch),
      ),
    movePanelToTab: (panelId, tabId) =>
      edit('movePanelToTab', panelId, config =>
        movePanelToTab(config, panelId, tabId),
      ),
    setPresentation: (panelId, presentation) =>
      edit('setPresentation', panelId, config =>
        setPresentation(config, panelId, presentation),
      ),
    setPanelClick: (panelId, click) =>
      edit('setPanelClick', panelId, config =>
        setPanelClick(config, panelId, click),
      ),
    referToSaved(panelId, instance) {
      if (!host.draft()) return;
      host.seed(instance);
      edit('referToSaved', panelId, config =>
        referToSaved(config, panelId, instance.id),
      );
    },
    addTab(title, firstTitle) {
      const draft = host.draft();
      const result = draft && addTab(draft, title, firstTitle);
      if (!draft || !result) return null;
      edit('addTab', result.id, config =>
        config === draft
          ? result.config
          : (addTab(config, title, firstTitle)?.config ?? config),
      );
      return result.id;
    },
    renameTab: (tabId, title) =>
      edit('renameTab', tabId, config => renameTab(config, tabId, title)),
    moveTab: (tabId, index) =>
      edit('moveTab', tabId, config => moveTab(config, tabId, index)),
    removeTab: tabId =>
      edit('removeTab', tabId, config => removeTab(config, tabId)),
    place: (panelId, layout) =>
      edit('place', panelId, config => placePanelIn(config, panelId, layout)),
    reorderPanel: (panelId, step) =>
      edit('reorderPanel', panelId, config =>
        reorderPanelIn(config, panelId, step),
      ),
    undo: () => rewind('undo'),
    redo: () => rewind('redo'),
    forget() {
      history.clear();
      return history.state;
    },
    defaultOf(name) {
      const draft = host.draft();
      return (
        (draft &&
          filtersOf(draft).find(field => field.name === name)?.default) ??
        null
      );
    },

    addFilter(filter) {
      const draft = host.draft();
      const result = draft && addFilter(draft, filter);
      if (!draft || !result) return null;
      edit('addFilter', result.name, config =>
        config === draft
          ? result.config
          : (addFilter(config, filter)?.config ?? config),
      );
      return result.name;
    },
    renameFilter: (name, label) =>
      edit('renameFilter', name, config => renameFilter(config, name, label)),
    retypeFilter: (name, type) =>
      edit('retypeFilter', name, config => retypeFilter(config, name, type)),
    removeFilter: name =>
      edit('removeFilter', name, config => removeFilter(config, name)),
    setFilterDefault: (name, value) =>
      edit('setFilterDefault', name, config =>
        setFilterDefault(config, name, value),
      ),
    setFilterRequired: (name, required) =>
      edit('setFilterRequired', name, config =>
        setFilterRequired(config, name, required),
      ),
    setFilterMultiple: (name, multiple) =>
      edit('setFilterMultiple', name, config =>
        setFilterMultiple(config, name, multiple),
      ),
    setFilterOptions: (name, options) =>
      edit('setFilterOptions', name, config =>
        setFilterOptions(config, name, options),
      ),
    moveFilter: (name, index) =>
      edit('moveFilter', name, config => moveFilter(config, name, index)),
    bindPanel(name, panelId, panelField) {
      const draft = host.draft();
      if (!draft) return [];
      const { connected } = bindPanel(
        draft,
        name,
        panelId,
        panelField,
        host.fieldsOf,
      );
      edit(
        'bindPanel',
        name,
        config =>
          bindPanel(config, name, panelId, panelField, host.fieldsOf).config,
      );
      return connected;
    },
    unbindPanels: (name, panelIds) =>
      edit('unbindPanels', name, config =>
        unbindPanels(config, name, panelIds),
      ),
    setTimeGrouping: grouping =>
      edit('setTimeGrouping', null, config =>
        setTimeGrouping(config, grouping),
      ),
  };
}
