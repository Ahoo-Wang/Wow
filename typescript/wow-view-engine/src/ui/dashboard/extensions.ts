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

import { createContext, useContext, type ReactNode } from 'react';

/** Where a panel added from the edit bar goes: its tab, from the first row on screen. */
export interface NewPanelSpot {
  /** The first grid row the reader can see; the panel lands there or below. */
  fromRow: number;
  /** The tab on screen; the board's first when left out. */
  tab?: string;
}

/**
 * The parts of building a board that live outside the edit bar and the
 * panel menu (D22 C–E): the big 「新建分析」 dialog, the presentation editor,
 * promoting an owned analysis to a saved view, and the tab bar. Each is
 * optional, and **an entry for one exists only while it is provided** — a
 * menu item that leads nowhere is a promise the board cannot keep (D4).
 *
 * The editing UI reads it from `DashboardEditExtensionsContext`, so whoever
 * implements a part provides it around `DashboardWorkbench` (or inside it)
 * without the grid, the bar or the menu learning a new prop.
 */
export interface DashboardEditExtensions {
  /**
   * 「新建分析…」, in the add menu and on an empty board: opens the dialog
   * a board-owned analysis is made in, which adds its panel with
   * `addPanel({ kind: 'view', owned }, spot)`.
   */
  onAddOwnedAnalysis?(spot: NewPanelSpot): void;
  /** 「改这里的展示…」 on a data panel: its presentation override. */
  onEditPresentation?(panelId: string): void;
  /**
   * 「恢复为视图的样子」 on a panel that wears a look of its own: its override
   * dropped (`setPresentation(panelId, null)`). The menu offers it only on a
   * panel with an override.
   */
  onResetPresentation?(panelId: string): void;
  /** 「另存为视图…」 on a panel that owns its analysis. */
  onSaveOwnedAsView?(panelId: string): void;
  /**
   * The tab bar, drawn between the edit bar and the panels. Which tab is on
   * screen is not an extension: it is the runtime's (`DashboardController.tab`,
   * since only that tab runs), and the grid and a new panel's place read it
   * there.
   */
  tabBar?: ReactNode;
}

const NONE: DashboardEditExtensions = {};

export const DashboardEditExtensionsContext =
  createContext<DashboardEditExtensions>(NONE);

/** The extensions in force; none by default. */
export function useDashboardEditExtensions(): DashboardEditExtensions {
  return useContext(DashboardEditExtensionsContext);
}
