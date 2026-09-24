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
import type { FilterValue } from './filter.js';
import type { ViewConfigBase } from './config.js';
import type {
  AnalysisDateUnit,
  AnalysisLayout,
  AnalysisTableSpec,
  AnalysisViewConfig,
} from './analysis.js';
import type { ChartSpec } from './chart.js';
import type { RecordLayout } from './record.js';

/**
 * A board's filter (D22 F): one chip on the filter bar, wired to a field of
 * each panel it narrows. It spans definitions, so the config declares it
 * rather than any one definition.
 *
 * Its value is the reader's and is never saved (`DashboardFilters`); what
 * the config says is what it starts at — `default` — and how it may be set.
 */
export interface DashboardField {
  /** The key the bindings, the values and the host's address name it by. */
  name: string;
  label: string;
  /**
   * A field kind of one of the five filter types (`filterTypeOf`), which is
   * the family of panel fields it can be wired to.
   */
  kind: FieldKindId;
  /**
   * A fixed list to pick from: the filter's value source is this list rather
   * than the values its wired fields hold (D22 G, 「值从哪来」). An `enum`
   * filter's choices are always such a list.
   */
  options?: FieldOption[];
  /**
   * An id filter's candidate source, as a `reference` field names the host's
   * (`FieldDefinition.remote`). Wiring it to a field that names one takes
   * that one when it has none (`bindPanel`).
   */
  remote?: string;
  /**
   * The value the filter holds until a reader sets another, in the shape its
   * operator takes (`filterOperatorOf`): a date window, a list of values, a
   * yes or no. What a board starts at is the config; what a reader set is
   * not.
   */
  default?: FilterValue;
  /**
   * Always has a value: clearing it goes back to `default`, which it must
   * therefore have — so a board never runs with it empty.
   */
  required?: true;
  /** May hold several values at once: 华东 or 华南, not only one of them. */
  multiple?: true;
}

/**
 * The five kinds of board filter (D22 F), each a family of the field kinds
 * it can be wired to: a date filter reaches a `date` or a `datetime`, a text
 * one a `string` or an `enum`, an id one a `reference`, a number and a
 * yes-or-no their own. "Same kind" in wiring and auto-connect means the same
 * family.
 */
export type DashboardFilterType = 'date' | 'text' | 'id' | 'number' | 'boolean';

/** The field kinds of each filter type, the first being what a new one is. */
export const DASHBOARD_FILTER_KINDS = {
  date: ['datetime', 'date'],
  text: ['string', 'enum'],
  id: ['reference'],
  number: ['number'],
  boolean: ['boolean'],
} as const satisfies Record<DashboardFilterType, readonly FieldKindId[]>;

/** The five types, in the order a choice among them is offered. */
export const DASHBOARD_FILTER_TYPES: readonly DashboardFilterType[] = [
  'date',
  'text',
  'id',
  'number',
  'boolean',
];

/**
 * The filter type a field kind belongs to, or `null` for a kind outside the
 * five — an array, a search, a metadata handle, one a host added. A filter
 * of such a kind still works, as a family of one: it wires to fields of
 * that very kind and is asked the way the kind asks (`sameFilterType`).
 */
export function filterTypeOf(kind: unknown): DashboardFilterType | null {
  for (const type of DASHBOARD_FILTER_TYPES)
    if ((DASHBOARD_FILTER_KINDS[type] as readonly unknown[]).includes(kind))
      return type;
  return null;
}

/**
 * Whether a filter of one kind can be wired to a field of another: the same
 * filter type, or — outside the five — the very same kind.
 */
export function sameFilterType(filter: unknown, field: unknown): boolean {
  const type = filterTypeOf(filter);
  return type === null ? filter === field : type === filterTypeOf(field);
}

/** Filters one board may hold. */
export const MAX_DASHBOARD_FILTERS = 20;

/**
 * The board's time grouping (D22 F, 整板 按日／周／月): one choice among
 * `units` that sets the date unit of every panel's time dimension, where the
 * panel's definition allows that unit. Like a filter's, its value is the
 * reader's; `default` is what the board starts at.
 */
export interface DashboardTimeGrouping {
  /** The units offered, in the order the bar shows them. */
  units: AnalysisDateUnit[];
  default: AnalysisDateUnit;
}

/**
 * What a board's filters hold right now: the reader's, never the config's
 * (D22 F「筛选值写进地址，不写进配置」). A host keeps it in its address
 * through `DashboardWorkbench`'s `initialFilters` / `onFiltersChange`.
 */
export interface DashboardFilters {
  /** Each filter's value by its name; a filter not here holds nothing. */
  values: Record<string, FilterValue>;
  /** The time grouping's unit, on a board that has one. */
  unit?: AnalysisDateUnit;
  /**
   * The panel each filter's value was pressed on, by filter name (D22 I,
   * cross-filtering): that panel is not narrowed by the value it set, and
   * marks the group instead. Set by a press, gone the moment the value is
   * set any other way or cleared — it says where this value came from.
   */
  from?: Record<string, string>;
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
  /**
   * What a press on one group of the panel does (D22 H, I): left out, the
   * analysis view's own follow-up menu, whose destinations open in the
   * host's workbench. Only an analysis panel has groups to press.
   */
  click?: PanelClick;
}

/**
 * What a press on one group of an analysis panel does, set by the board's
 * author (D22 I, 「点击时…」). The default — no `click` — is the follow-up
 * menu (D22 H), which is not a choice stored here.
 */
export type PanelClick =
  /**
   * Cross-filtering: the board filter named takes the group's value on the
   * dimension it is wired to on this panel, and every other panel wired to
   * it runs under that value. The panel pressed is not narrowed by it — it
   * marks the group — and the same group pressed again clears it.
   */
  | { kind: 'filter'; filter: string }
  /**
   * Another saved record or analysis view, opened through the host's route
   * under the group pressed: each of its conditions on a field the view's
   * data has too, by name and type.
   */
  | { kind: 'view'; instanceId: string }
  /**
   * A page of the host's: `url` with every `{{field}}` in it replaced by the
   * group's value on the dimension over that field, encoded.
   */
  | { kind: 'url'; url: string };

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

/**
 * Wires a board filter to a field of the panel's view: the filter narrows
 * the panel through that field. A panel not wired to a filter is not
 * narrowed by it, and says so (D22 F「不受此筛选影响」).
 */
export interface PanelBinding {
  globalField: string;
  panelField: string;
  /**
   * Made by auto-connect — the field has the name and the type of one the
   * author wired by hand on another panel (D22 G) — rather than chosen for
   * this panel. Left out for a binding the author chose.
   */
  auto?: true;
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
  /** The board's filters, in the order the filter bar shows them. */
  fields: DashboardField[];
  /** The board's time grouping; a board without one leaves it out. */
  timeGrouping?: DashboardTimeGrouping;
  panels: DashboardPanel[];
}

/**
 * A dashboard config has no presentation-only member of its own: every one
 * of `fields` and `panels` reaches the panels' queries or what they draw
 * from them (`PRESENTATION_MEMBERS` in `config.ts`).
 */
export const DASHBOARD_PRESENTATION_MEMBERS =
  [] as const satisfies readonly (keyof DashboardViewConfig)[];
