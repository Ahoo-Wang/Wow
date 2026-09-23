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
import type { FilterTree } from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
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
  /** 在工作台中打开: the view it shows, under the board's filter mapped onto it. */
  open?(): void;
  /** 刷新这个面板. */
  refresh?(): void;
  /** 改标题: its title (a heading's words) edited in place. */
  rename?(): void;
  /** While the title is being edited in place: take or drop the new one. */
  renaming?: { commit(title: string): void; cancel(): void };
  editPresentation?(): void;
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
  onOpenView?(instanceId: string, filter: FilterTree | null): void;
  messages: MessageFormatters;
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
  onOpenView,
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
  // which is the only form another view can read it in.
  if (onOpenView && shown !== undefined && child)
    commands.open = () => onOpenView(shown, child.scopeFilter);
  if (child) commands.refresh = () => dashboard.refreshPanel(id);

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
    if (extensions.onEditPresentation) {
      const present = extensions.onEditPresentation;
      commands.editPresentation = () => present(id);
    }
    if (owned && extensions.onSaveOwnedAsView) {
      const promote = extensions.onSaveOwnedAsView;
      commands.saveAsView = () => promote(id);
    }
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
      move: tabId => edit.movePanelToTab(id, tabId),
    };
  }
  return commands;
}
