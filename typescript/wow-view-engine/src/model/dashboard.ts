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

import type { FieldKindId, FieldOption } from './field.js';
import type { ViewConfigBase } from './config.js';
import type {
  AnalysisLayout,
  AnalysisTableSpec,
  AnalysisViewConfig,
} from './analysis.js';
import type { ChartSpec } from './chart.js';
import type { RecordLayout } from './record.js';

/**
 * A dashboard's own filter field. It spans definitions, so it is declared by
 * the config rather than by one definition.
 */
export interface DashboardField {
  name: string;
  label: string;
  kind: FieldKindId;
  options?: FieldOption[];
}

export interface DashboardPanelBase {
  id: string;
  title?: string;
  layout: PanelLayout;
  /**
   * The tab the panel sits on, by `DashboardTab.id`. A board without tabs
   * leaves it out; on a board with tabs a panel naming none of them is read
   * as sitting on the first (`panelTab`), and admission says so.
   */
  tab?: string;
}

/**
 * Columns of the dashboard grid. A panel's `x + w` stays within it, otherwise
 * the layout adapter would wrap or drop the panel instead of placing it.
 *
 * A config says which grid its numbers are in (`DashboardViewConfig.columns`)
 * rather than leaving a reader to guess from the widest panel: a board whose
 * panels all sit in the left half reads the same either way.
 */
export const DASHBOARD_GRID_COLUMNS = 24;

/**
 * The grid a config was laid out in before it said so. A stored config with
 * no `columns` is in it, and is read into `DASHBOARD_GRID_COLUMNS` by doubling
 * every `x` and `w` (`migrateDashboardConfig`), which draws every panel at
 * the same pixels (D22 E).
 */
export const LEGACY_GRID_COLUMNS = 12;

/** Characters one markdown panel may hold; a config arrives from a store. */
export const MAX_MARKDOWN_LENGTH = 20_000;

/** Characters one heading panel may hold: a section title, one line. */
export const MAX_HEADING_LENGTH = 200;

/** Links one links panel may hold. */
export const MAX_PANEL_LINKS = 50;

/** Tabs one dashboard may hold. */
export const MAX_DASHBOARD_TABS = 20;

/** Grid geometry; coordinates are non-negative and sizes positive integers. */
export interface PanelLayout {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * One tab of a board: panels are grouped under it, and the global filter sits
 * above them all. Zero or one tab draws no tab bar.
 */
export interface DashboardTab {
  id: string;
  title: string;
}

/**
 * How a panel looks at its view, over what the view itself says (D22 D):
 * the layout, the chart and the table's totals row — the members of a view
 * that draw the result rather than ask for it. Never the question: the
 * dimensions, metrics, conditions and order stay the view's.
 */
export interface PanelPresentation {
  layout?: AnalysisLayout | RecordLayout;
  chart?: ChartSpec;
  table?: AnalysisTableSpec;
}

/** Every member a presentation override may set. */
export const PANEL_PRESENTATION_MEMBERS = [
  'layout',
  'chart',
  'table',
] as const satisfies readonly (keyof PanelPresentation)[];

/**
 * An analysis view that lives only in this dashboard (D22 C): saved, deleted
 * and shared with the board, never listed among the definition's views. Its
 * config is judged against its definition exactly as a saved one is.
 */
export interface OwnedView {
  definitionId: string;
  config: AnalysisViewConfig;
}

interface DashboardViewPanelBase extends DashboardPanelBase {
  kind: 'view';
  bindings: PanelBinding[];
  presentation?: PanelPresentation;
}

/**
 * Data panel: shows a record or analysis view and is filtered by the board.
 * The view is either a saved one (`instanceId`) or one the board owns
 * (`owned`) — exactly one of the two.
 */
export type DashboardViewPanel = DashboardViewPanelBase &
  (
    | { instanceId: string; owned?: never }
    | { owned: OwnedView; instanceId?: never }
  );

/** Maps a dashboard field onto a field of the referenced definition. */
export interface PanelBinding {
  globalField: string;
  panelField: string;
}

/**
 * Content panels are static: no query, no global filter, no child runtime.
 * A heading is a section's title across the board, one line of plain text.
 */
export type DashboardContentPanel = DashboardPanelBase &
  (
    | { kind: 'heading'; content: string }
    | { kind: 'markdown'; content: string }
    | {
        kind: 'image';
        src: string;
        alt?: string;
        fit?: 'contain' | 'cover';
        href?: string;
      }
    | { kind: 'links'; items: DashboardLink[] }
  );

export type ContentPanelKind = DashboardContentPanel['kind'];

export interface DashboardLink {
  label: string;
  href: string;
  description?: string;
}

export type DashboardPanel = DashboardViewPanel | DashboardContentPanel;

export interface DashboardViewConfig extends ViewConfigBase {
  kind: 'dashboard';
  /** The grid the layouts are written in; see `DASHBOARD_GRID_COLUMNS`. */
  columns: typeof DASHBOARD_GRID_COLUMNS;
  /** In the order the tab bar shows them; see `DashboardTab`. */
  tabs: DashboardTab[];
  fields: DashboardField[];
  panels: DashboardPanel[];
}

/**
 * A dashboard config has no presentation-only member of its own: every one
 * of `fields` and `panels` reaches the panels' queries or what they draw
 * from them (`PRESENTATION_MEMBERS` in `config.ts`).
 */
export const DASHBOARD_PRESENTATION_MEMBERS =
  [] as const satisfies readonly (keyof DashboardViewConfig)[];
