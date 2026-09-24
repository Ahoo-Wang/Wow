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

import { createContext, useContext } from 'react';
import {
  isContentPanel,
  isOwnedPanel,
  panelTab,
  referencedInstance,
} from '../../dashboard/index.js';
import { PANEL_PRESENTATION_MEMBERS } from '../../model/index.js';
import {
  ownedNavigation,
  type DashboardController,
  type DashboardPanelView,
} from '../../react/index.js';
import {
  hasResult,
  isRecordRuntime,
  type RecordViewRuntime,
  type ViewNavigation,
} from '../../runtime/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import type { DashboardEditExtensions } from './extensions.js';

/**
 * What the board's builder — `DashboardBoard` — does for a panel that the
 * panel cannot do alone: the dialogs it opens are the board's, one at a
 * time, and the title being renamed in place is one at a time too.
 */
export interface BoardBuilding {
  /** The panel whose title is being edited in place, if any. */
  renaming: string | null;
  /** Starts (an id) or ends (`null`) renaming in place. */
  rename(panelId: string | null): void;
  /** Opens the picker to point a data panel at another saved view. */
  replace(panelId: string): void;
  /** Opens the form a content panel's note, picture or links are edited in. */
  editContent(panelId: string): void;
  /**
   * A panel was taken off the board: the builder says so and takes the
   * focus, since the control that asked is gone with the panel.
   */
  removed(name: string): void;
  /** A panel was copied, and the copy is called this. */
  duplicated(name: string): void;
  /** A panel went to another tab, called this on the bar. */
  moved(name: string, tab: string): void;
  /** Opens 「点击时…」 for an analysis panel (D22 I). */
  click(panelId: string): void;
}

export const BoardBuildingContext = createContext<BoardBuilding | null>(null);

/**
 * Where a dialog the board opened hands the keyboard back as it closes. A
 * dialog opened from a menu item would return it to that item, which went
 * with the menu; so the board names the control that asked instead — the
 * edit bar's 「＋ 添加」, or the panel's own 「⋯」 — asked at closing, since
 * the board has changed under the dialog by then.
 */
export type FinalFocus = () => HTMLElement | boolean;

/** The builder around the grid; `null` where nothing builds the board. */
export function useBoardBuilding(): BoardBuilding | null {
  return useContext(BoardBuildingContext);
}

/**
 * What one panel's menu offers, each present only when it can be done
 * (D4): 「看」 always, 「改」 while the board is being built.
 */
export interface PanelCommands {
  /**
   * 在工作台中打开: the view it shows, under the board's filters mapped onto
   * it — or, for an analysis the board owns, that analysis unsaved.
   */
  open?(): void;
  /** 刷新这个面板. */
  refresh?(): void;
  /**
   * 导出数据…: the record view whose rows the export window takes (D14,
   * D22 运维). Only a record panel with rows on screen: an export over no
   * result would make an empty file (P-17).
   */
  exportRows?: RecordViewRuntime;
  /**
   * 复制为共享视图并替换…: the personal view a shared board stands on,
   * copied for the board's readers and the panel pointed at the copy
   * (D22 B).
   */
  copyAsShared?(): void;
  /** 改标题: its title (a heading's words) edited in place. */
  rename?(): void;
  /** While the title is being edited in place: take or drop the new one. */
  renaming?: { commit(title: string): void; cancel(): void };
  editPresentation?(): void;
  /** 点击时…: what a press on one of its groups does (D22 I). */
  click?(): void;
  /** 恢复为视图的样子: only on a panel that wears a look of its own. */
  resetPresentation?(): void;
  editContent?(): void;
  replace?(): void;
  duplicate?(): void;
  /** 移到标签页 ›: the board's other tabs, by the name the bar shows. */
  moveTo?: {
    tabs: readonly { id: string; name: string }[];
    move(tabId: string): void;
  };
  saveAsView?(): void;
  /** 从仪表盘移除, which the panel asks about first. */
  remove?(): void;
  /** What removing it costs, for the question asked before it goes. */
  removes: 'view' | 'owned' | 'content';
}

export interface PanelCommandInput {
  panel: DashboardPanelView;
  name: string;
  dashboard: DashboardController;
  /** The builder, and whether the board is being built right now. */
  building: BoardBuilding | null;
  editing: boolean;
  /**
   * The one-column reading of a narrow screen: there the board is built by
   * renaming and removing alone (D22 J) — nothing placed in a derived
   * column could be written back, and a copy or a new tab is a placement.
   */
  narrow: boolean;
  extensions: DashboardEditExtensions;
  /** The host's route (`ViewNavigation`); no route, no 在工作台中打开. */
  onNavigate?(to: ViewNavigation): void;
  /** Whether a record panel offers 导出数据… (`DashboardGrid.panelExport`). */
  exports?: boolean;
  messages: MessageFormatters;
}

/**
 * The record view a panel's 导出数据… takes, when the board offers exports
 * and the panel has rows on screen to take.
 */
function exportable(
  panel: DashboardPanelView,
  exports: boolean | undefined,
): RecordViewRuntime | undefined {
  const child = panel.runtime;
  return exports &&
    child &&
    isRecordRuntime(child) &&
    hasResult(child.getSnapshot())
    ? child
    : undefined;
}

/**
 * The menu of a board that is only read (an embed's read-only tier): 导出数据…
 * where the page switched exports on, and nothing else — a read-only board
 * answers no press and opens nothing (D24 Q24: an export is a switch, not a
 * tier). `undefined`, and no menu, otherwise.
 */
export function readerCommands(
  panel: DashboardPanelView,
  exports: boolean | undefined,
): PanelCommands | undefined {
  const rows = exportable(panel, exports);
  return rows ? { removes: 'view', exportRows: rows } : undefined;
}

/** Whether a panel carries the finding 「复制为共享视图并替换」 answers. */
function standsOnPersonalView(panel: DashboardPanelView): boolean {
  return panel.issues.some(
    found => found.code === 'dashboard.panel.scope-too-narrow',
  );
}

/** Whether a panel overrides how its view looks (D22 D). */
function hasOwnLook(panel: DashboardPanelView['panel']): boolean {
  if (panel.kind !== 'view') return false;
  const look: unknown = panel.presentation;
  return (
    typeof look === 'object' &&
    look !== null &&
    Object.keys(look).some(key =>
      (PANEL_PRESENTATION_MEMBERS as readonly string[]).includes(key),
    )
  );
}

/** One panel's commands, read off the board as it stands. */
export function panelCommands({
  panel,
  name,
  dashboard,
  building,
  editing,
  narrow,
  extensions,
  onNavigate,
  exports,
  messages,
}: PanelCommandInput): PanelCommands {
  const id = panel.id;
  const stored = panel.panel;
  const shown = referencedInstance(stored);
  const child = panel.runtime;
  const content = isContentPanel(stored);
  const owned = isOwnedPanel(stored);
  const commands: PanelCommands = {
    removes: content ? 'content' : owned ? 'owned' : 'view',
  };
  // The view it shows opens where views are worked on, under the board's
  // condition as this panel carries it — mapped onto the view's own fields,
  // which is the only form another view can read it in. An analysis the
  // board owns has no saved view to open, so it goes unsaved, the board's
  // conditions its own (「打开源视图」, batch D).
  if (onNavigate && shown !== undefined && child)
    commands.open = () =>
      onNavigate({
        kind: 'view',
        instanceId: shown,
        filter: child.scopeFilter,
      });
  else if (onNavigate && owned && child?.kind === 'analysis')
    commands.open = () => {
      const to = ownedNavigation(child, name);
      if (to) onNavigate(to);
    };
  if (child) commands.refresh = () => dashboard.refreshPanel(id);
  const rows = exportable(panel, exports);
  if (rows) commands.exportRows = rows;

  const edit = dashboard.edit;
  if (!editing || !building || !edit) return commands;

  commands.rename = () => building.rename(id);
  if (building.renaming === id)
    commands.renaming = {
      commit: title => {
        // A heading's words are what it is called; any other panel keeps
        // what it holds and takes a title.
        if (stored.kind === 'heading')
          edit.editPanelContent(id, { kind: 'heading', content: title.trim() });
        else edit.renamePanel(id, title);
        building.rename(null);
      },
      cancel: () => building.rename(null),
    };
  commands.remove = () => {
    edit.removePanel(id);
    building.removed(name);
  };
  if (narrow) return commands;

  if (!content) {
    commands.replace = () => building.replace(id);
    // A look is an analysis's to change here: a chart type, its options,
    // the totals row. A record panel has nothing the visualization panel
    // could draw.
    if (extensions.onEditPresentation && child?.kind === 'analysis') {
      const present = extensions.onEditPresentation;
      commands.editPresentation = () => present(id);
    }
    // What a press does is an analysis's to set: a record view has rows,
    // not groups (D22 I).
    if (child?.kind === 'analysis') commands.click = () => building.click(id);
    if (extensions.onResetPresentation && hasOwnLook(stored)) {
      const reset = extensions.onResetPresentation;
      commands.resetPresentation = () => reset(id);
    }
    if (owned && extensions.onSaveOwnedAsView) {
      const promote = extensions.onSaveOwnedAsView;
      commands.saveAsView = () => promote(id);
    }
    // Only over a view this reader has open — the one someone else cannot
    // read is theirs to copy — and only where they may make a shared one.
    const source = child?.getSnapshot()?.saved;
    const share = extensions.copyAsShared;
    if (
      share &&
      shown !== undefined &&
      source?.id === shown &&
      standsOnPersonalView(panel) &&
      share.offered(source.definitionId)
    )
      commands.copyAsShared = () => share.open(id);
  } else if (stored.kind !== 'heading') {
    commands.editContent = () => building.editContent(id);
  }
  commands.duplicate = () => {
    if (edit.duplicatePanel(id) !== null) building.duplicated(name);
  };
  const tabs = dashboard.tabs;
  if (tabs.length > 1) {
    // The kernel's reading: a panel naming no tab of the board is on its first.
    const here = panelTab({ tabs: [...tabs] }, stored);
    commands.moveTo = {
      tabs: tabs.flatMap((tab, index) =>
        tab.id === here
          ? []
          : [
              {
                id: tab.id,
                name:
                  tab.title.trim() ||
                  messages.label('label.dashboard.tab.untitled', {
                    index: index + 1,
                  }),
              },
            ],
      ),
      move: tabId => {
        edit.movePanelToTab(id, tabId);
        const to = commands.moveTo?.tabs.find(tab => tab.id === tabId);
        if (to) building.moved(name, to.name);
      },
    };
  }
  return commands;
}
