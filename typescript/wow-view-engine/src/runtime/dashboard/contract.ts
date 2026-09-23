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
 * What a dashboard runtime is to the rest of the package: its snapshot, its
 * public face and what it is built from. `DashboardViewRuntime` is the one
 * class behind it.
 */

import type {
  DashboardDefinition,
  DashboardViewConfig,
  FilterTree,
  PanelLayout,
  RuntimeLimits,
  ViewInstance,
  ViewScope,
} from '../../model/index.js';
import type { FieldKindRegistry } from '../../filter/index.js';
import type { PanelDefinition } from '../../dashboard/index.js';
import type { RuntimeEnvironment } from '../environment.js';
import type { OptionSource } from '../source.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { ViewRuntime, ViewRuntimeState } from '../viewRuntimeTypes.js';
import type { PanelRuntimeFactory } from './children.js';
import type { DashboardEditing } from './editing.js';
import type { DashboardPanelState } from './panels.js';
import type { PanelResolver } from './references.js';

export interface DashboardRuntimeState extends ViewRuntimeState<DashboardViewConfig> {
  /**
   * The applied panels. Loading, errors and data are each panel's own: a
   * dashboard has no single query state to report.
   */
  panels: DashboardPanelState[];
  /** True while a panel reference is still being loaded. */
  resolving: boolean;
  /**
   * The tab on screen (D22 E): the one asked for (`showTab`) while the board
   * has it, else its first; `null` on a board without tabs. Only its panels
   * run. It is the reader's, not the board's — never in the config, so
   * switching tabs never makes a board dirty.
   */
  tab: string | null;
}

/**
 * The public face of a dashboard runtime: a view runtime whose snapshot also
 * carries the panels. `open` narrows to it by `kind`, so a caller reaches the
 * panels without knowing the class behind them.
 */
export interface DashboardRuntime
  extends ViewRuntime<DashboardViewConfig>, DashboardEditing {
  getSnapshot(): DashboardRuntimeState;
  /** Resolves once every panel reference has been loaded or refused. */
  ready(): Promise<void>;
  /** The child runtime of one panel, for a host that drives a panel itself. */
  panelRuntime(panelId: string): DataViewRuntime | null;
  /**
   * Puts one panel at `layout` and applies that alone: the panels it now
   * covers make way and its tab floats up behind it (`placePanel`), and
   * every other pending edit — a global filter not yet applied, say — stays
   * pending. A layout the grid does not admit, or a panel id there is none
   * of, is ignored.
   */
  place(panelId: string, layout: PanelLayout): void;
  /** Re-runs one panel on what it has applied — a retry after it failed. */
  refreshPanel(panelId: string): void;
  /**
   * Loads a saved view a panel is about to show, and resolves once it has
   * settled — read or found unreadable, never rejecting. `addPanel` sizes a
   * saved view by what it shows only once it is loaded (a metric card a
   * quarter, a table the full width), so the board's 「添加」 waits for this
   * first; the child then starts on the reference already in hand.
   */
  preload(instanceId: string): Promise<void>;
  /**
   * Shows one tab: its panels run — those shown before keep their rows and
   * run only when what they ask changed, or a refresh went by while they
   * were away — and the other tabs' panels stand still (D22 E). A tab the
   * board lacks shows its first.
   */
  showTab(tabId: string | null): void;
}

export interface DashboardRuntimeOptions {
  id: string;
  definition: DashboardDefinition;
  config: DashboardViewConfig;
  title: string;
  scope: ViewScope;
  saved?: ViewInstance | null;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  resolve: PanelResolver;
  /**
   * The definition a view the board owns is of; `null` for one this release
   * does not declare. Definitions are code, so this answers at once.
   */
  definitions(definitionId: string): PanelDefinition | null;
  createPanelRuntime: PanelRuntimeFactory;
  /** See `ViewRuntime.optionSource`. */
  resolveOptions?(key: string): OptionSource;
  /** An outer condition in force from the first execution, as for a data view. */
  scopeFilter?: FilterTree | null;
}
