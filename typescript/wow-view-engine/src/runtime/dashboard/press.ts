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
 * A press on one group of an analysis panel, worked out (D22 H, I): the
 * group's value in a board filter's shape and the filter it sets
 * (cross-filtering), whether a group is the one a filter's value was
 * pressed on, and where a custom destination goes carrying it. The panel's
 * `click` says which; the kernel reads it (`dashboard/click.ts`), and this
 * is the half that needs the analysis kernel — a group's conditions
 * (`drillGroups`) — which the dashboard kernel may not import.
 *
 * Which click is in force is the panel state's to say, never a press's
 * (A-11): admission sets a click aside when it warned of it, or when the
 * host holds the filter it sets, and the state carries the click with the
 * finding (`DashboardPanelState.click`, `clickFinding`). A press reads
 * that and falls back to the follow-up menu with the finding, as the
 * panel's warning says (D22 H).
 */

import { dequal } from 'dequal';
import {
  filterTypeOf,
  sameFilterType,
  type DashboardField,
  type DashboardFilters,
  type DashboardViewConfig,
  type ViewInstance,
  type DashboardViewPanel,
  type FieldDefinition,
  type FilterLeaf,
  type FilterNode,
  type FilterTree,
  type FilterValue,
  type Issue,
  type IssuePath,
  type RecordData,
} from '../../model/index.js';
import {
  filterFields,
  isReferenceFilterValue,
  issue,
  readInstant,
  type FieldKindRegistry,
} from '../../filter/index.js';
import { drillGroups, type DrilledGroup } from '../../analysis/index.js';
import { drillSpan } from '../../analysis/drill.js';
import {
  bindingsOf,
  crossFilterChoices,
  defaultFilters,
  fillUrl,
  filterValueIssues,
  filtersOf,
  isViewPanel,
  panelsOf,
  takesGroup,
  type BoardClick,
  type PanelReference,
} from '../../dashboard/index.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { DashboardPanelState } from './panels.js';
import type { HandOver, ViewNavigation } from '../navigation.js';

/** What a press that sets a filter did (D22 I). */
export type CrossFilterOutcome =
  /** The filter took the group's value, from this panel. */
  | { kind: 'set'; filter: DashboardField }
  /** The same group pressed again: the filter no longer holds it. */
  | { kind: 'cleared'; filter: DashboardField }
  /**
   * Nothing changed: the group holds no value the filter can take (the
   * records without one), or the filter refused it.
   */
  | { kind: 'no-value'; filter: DashboardField }
  /** The panel's press sets no filter. */
  | { kind: 'none' };

/** Where a press on a custom destination goes, or why it goes nowhere. */
export type PressDestination =
  | { to: ViewNavigation }
  | { refused: Issue }
  /**
   * The click cannot do what it says — admission warned of it, or, read at
   * the press, a board it opens is gone, or a filter or a dimension it maps
   * is (D23 Q17): the follow-up menu opens on the group instead, and
   * `issue` says why. The board read by the press re-judges the panel, so
   * the warning stays on it and the next press opens the menu directly.
   */
  | { fallback: Issue };

/** Another board as a click reads it: the instance, its config a board's. */
export type DestinationBoard = ViewInstance & { config: DashboardViewConfig };

/** What the presses need of the runtime that holds the board. */
export interface PressHost {
  kinds: FieldKindRegistry;
  applied(): DashboardViewConfig;
  filters(): DashboardFilters;
  /** A data panel's child, the rows a press is on. */
  child(panelId: string): DataViewRuntime | null;
  /** Sets a filter by a press (`FilterValues.press`). */
  press(name: string, value: FilterValue | null, panelId: string): Issue[];
  /** Whether the host holds this filter, so no press can set it. */
  held(name: string): boolean;
  /**
   * A panel as the board has it now: the click in force and why a click set
   * is not (`DashboardPanelState`); `null` for no such panel.
   */
  panel(panelId: string): DashboardPanelState | null;
  /** A saved view, loaded: what a destination carries its group into. */
  reference(instanceId: string): Promise<PanelReference | null>;
  /** What the panel's view would take off the board (`handOver`). */
  handOver(panelId: string): HandOver | null;
}

/** The panel, the child and the config its rows ran on. */
interface Pressed {
  panel: DashboardViewPanel;
  index: number;
  groups: DrilledGroup[];
}

/**
 * The presses on a board's panels. Each reads the panel as the board has it
 * now and the child's rows as they last landed — a press is on a group of
 * the result on screen, never of a draft.
 */
export class PanelPresses {
  constructor(private readonly host: PressHost) {}

  /**
   * Cross-filtering: the filter the panel's press sets takes the group's
   * value, marked as pressed on this panel, so every other panel wired to it
   * runs under it and this one does not; the same group pressed again clears
   * it — a required filter back to its default.
   */
  crossFilter(panelId: string, row: RecordData): CrossFilterOutcome {
    const target = this.filterOf(panelId);
    if (!target) return { kind: 'none' };
    const { filter, field } = target;
    const value = this.valueOf(panelId, filter, field, row);
    if (value === null) return { kind: 'no-value', filter };
    const current = this.host.filters();
    if (
      current.from?.[filter.name] === panelId &&
      dequal(current.values[filter.name], value)
    ) {
      this.host.press(filter.name, null, panelId);
      return { kind: 'cleared', filter };
    }
    const refused = this.host.press(filter.name, value, panelId);
    return refused.length > 0
      ? { kind: 'no-value', filter }
      : { kind: 'set', filter };
  }

  /**
   * Whether a filter holds this group's value, set by a press on this very
   * panel — the one its click sets, or a date filter a span of its time
   * axis set (`pressSpan`): the group the panel marks, since it is not
   * narrowed by the value it set. A group whose bucket lies inside the span
   * a date filter holds is one of the groups pressed.
   */
  pressed(panelId: string, row: RecordData): boolean {
    const found = this.panelOf(panelId);
    const current = this.host.filters();
    if (!found || !current.from) return false;
    const byName = new Map(
      filtersOf(this.host.applied()).map(field => [field.name, field]),
    );
    return bindingsOf(found.panel).some(binding => {
      const filter = byName.get(binding.globalField);
      if (!filter || current.from?.[filter.name] !== panelId) return false;
      const value = this.valueOf(panelId, filter, binding.panelField, row);
      const held = current.values[filter.name];
      return value !== null && (dequal(held, value) || within(value, held));
    });
  }

  /**
   * The date filters a span of this panel's time axis can set (D33 Q52,
   * 「设为〈筛选〉」): each wired to the panel through the field of a date
   * dimension its rows ran with, and not held by the host. Offered whatever
   * the panel's click says — a brush is not a press, and asks the menu.
   */
  spanFilters(panelId: string): DashboardField[] {
    const found = this.panelOf(panelId);
    const ran = this.host.child(panelId)?.getSnapshot().result?.config;
    if (!found || ran?.kind !== 'analysis' || ran.elements?.length) return [];
    const dates = ran.groups.filter(group => group.type === 'DATE_HISTOGRAM');
    return crossFilterChoices(
      filtersOf(this.host.applied()),
      found.panel,
      dates,
    )
      .map(choice => choice.filter)
      .filter(
        filter =>
          filterTypeOf(filter.kind) === 'date' && !this.host.held(filter.name),
      );
  }

  /**
   * A span of a panel's time axis set into one of its `spanFilters`, as a
   * press sets a filter (D22 I): marked as pressed on this panel, so every
   * other panel wired to it runs under it and this one keeps every group,
   * marking the ones inside it (D23 Q18). `row` and `through` are the span's
   * two ends, as `drillSpan` reads them.
   */
  pressSpan(
    panelId: string,
    name: string,
    row: RecordData,
    through: RecordData,
  ): CrossFilterOutcome {
    const filter = this.spanFilters(panelId).find(entry => entry.name === name);
    const found = this.panelOf(panelId);
    const child = this.host.child(panelId);
    const ran = child?.getSnapshot().result?.config;
    if (!filter || !found || !child || ran?.kind !== 'analysis')
      return { kind: 'none' };
    const field = bindingsOf(found.panel).find(
      binding => binding.globalField === name,
    )?.panelField;
    const spanned = drillSpan(
      ran,
      child.fields,
      this.host.kinds,
      row,
      through,
      {
        timeZone: child.environment.timeZone,
      },
    )?.find(
      entry =>
        entry.group.field === field && entry.group.type === 'DATE_HISTOGRAM',
    );
    const value = spanned ? filterValueOf('date', spanned) : null;
    if (value === null) return { kind: 'no-value', filter };
    return this.host.press(name, value, panelId).length > 0
      ? { kind: 'no-value', filter }
      : { kind: 'set', filter };
  }

  /**
   * Where a press on a panel with a custom destination goes: a page of the
   * host's, its URL filled with the group; or another saved view, taking
   * what the panel's view would take off the board (D26 Q30) — what the
   * page holds as its scope, the reader's values and the group's
   * conditions as its own — each condition on fields its data has too, by
   * name and by type, the rule auto-connect wires by (D22 G). A view that
   * is gone, or is a dashboard, is refused and said.
   */
  async destination(
    panelId: string,
    row: RecordData,
  ): Promise<PressDestination | null> {
    const state = this.host.panel(panelId);
    const click = state?.click ?? null;
    if (!click)
      return state?.clickFinding ? { fallback: state.clickFinding } : null;
    if (click.kind === 'filter') return null;
    const pressed = this.pressedOn(panelId, row);
    if (!pressed) return null;
    const at = ['panels', pressed.index, 'click'];
    if (click.kind === 'url') {
      const url = fillUrl(click.url, groupTexts(pressed.groups));
      return url === null
        ? { refused: issue('dashboard.click.url-unsafe', at) }
        : { to: { kind: 'url', url } };
    }
    if (click.kind === 'dashboard') return this.toBoard(click, pressed, at);
    const reference = await this.host.reference(click.instanceId);
    if (!reference)
      return {
        refused: issue('dashboard.click.destination-unavailable', at),
      };
    if (reference.instance.config.kind === 'dashboard')
      return {
        refused: issue('dashboard.click.destination-unsupported', at),
      };
    const carries = carriedOnto(
      this.host.child(panelId)?.fields ?? [],
      reference.fields,
    );
    const conditions: FilterLeaf[] = pressed.groups.flatMap(drilled =>
      carries(drilled.group.field) ? drilled.conditions : [],
    );
    const handed = this.host.handOver(panelId);
    const own = kept(handed?.filter ?? null, carries);
    return {
      to: {
        kind: 'view',
        definitionId: reference.instance.definitionId,
        instanceId: click.instanceId,
        scopeFilter: tree(kept(handed?.scopeFilter ?? null, carries)),
        filter: tree([...own, ...conditions]),
        ...(handed?.from ? { from: handed.from } : {}),
      },
    };
  }

  /**
   * Another board a click opens, read for 「点击时…」 or a press — never
   * when this board opens — through the references the panels load, so
   * the read re-judges the click (a reference settling admits the board
   * again); `null` for one gone, unreadable or not a board.
   */
  async board(instanceId: string): Promise<DestinationBoard | null> {
    const reference = await this.host.reference(instanceId);
    const config = reference?.instance.config;
    return reference && config?.kind === 'dashboard'
      ? { ...reference.instance, config }
      : null;
  }

  /**
   * A press that opens another board (D23 Q17): the board read — which
   * re-judges the panel's click against it, so the state says whether the
   * mapping still holds and, if not, why — then each filter mapped set
   * from its source — the group's value on a
   * dimension, in the filter's shape, or what this board's filter holds at
   * the press (its default until a reader set another) — and the rest at
   * the target's defaults, as a board opened without a value for them would
   * start. A source with nothing to give — the records without a value, a
   * filter left blank, a value the target filter refuses (several into one
   * that takes one) — leaves that filter at its default too.
   */
  private async toBoard(
    click: BoardClick,
    pressed: Pressed,
    at: IssuePath,
  ): Promise<PressDestination> {
    const reference = await this.host.reference(click.instanceId);
    const state = this.host.panel(pressed.panel.id);
    const config = reference?.instance.config;
    if (!state?.click || !reference || config?.kind !== 'dashboard')
      return {
        fallback:
          state?.clickFinding ?? issue('dashboard.click.board-gone', at),
      };
    const filters = defaultFilters(config);
    const byName = new Map(filtersOf(config).map(field => [field.name, field]));
    const held = this.host.filters().values;
    for (const [name, source] of Object.entries(click.values)) {
      const filter = byName.get(name);
      if (!filter) continue;
      const value =
        'filter' in source
          ? (held[source.filter] ?? null)
          : this.groupValue(filter, source.dimension, pressed);
      if (
        value !== null &&
        filterValueIssues(filter, value, this.host.kinds, [name]).length === 0
      )
        filters.values[name] = value;
    }
    return {
      to: {
        kind: 'dashboard',
        definitionId: reference.instance.definitionId,
        instanceId: click.instanceId,
        filters,
      },
    };
  }

  /** The group's value on one dimension, in a filter's shape; `null` for none. */
  private groupValue(
    filter: DashboardField,
    field: string,
    pressed: Pressed,
  ): FilterValue | null {
    const type = filterTypeOf(filter.kind);
    const drilled = pressed.groups.find(
      entry =>
        entry.group.field === field && takesGroup(type, entry.group.type),
    );
    return drilled ? filterValueOf(type, drilled) : null;
  }

  /** The filter a panel's press sets, and the field it is wired through. */
  private filterOf(
    panelId: string,
  ): { filter: DashboardField; field: string } | null {
    const panel = this.panelOf(panelId)?.panel;
    const click = this.host.panel(panelId)?.click;
    if (!panel || click?.kind !== 'filter') return null;
    const filter = filtersOf(this.host.applied()).find(
      field => field.name === click.filter,
    );
    const binding =
      filter &&
      bindingsOf(panel).find(entry => entry.globalField === filter.name);
    return filter && binding ? { filter, field: binding.panelField } : null;
  }

  private panelOf(
    panelId: string,
  ): { panel: DashboardViewPanel; index: number } | null {
    const panels = panelsOf(this.host.applied());
    const index = panels.findIndex(
      panel => isViewPanel(panel) && panel.id === panelId,
    );
    return index < 0
      ? null
      : { panel: panels[index] as DashboardViewPanel, index };
  }

  /** The row's groups, as the config the child's rows ran on reads them. */
  private pressedOn(panelId: string, row: RecordData): Pressed | null {
    const found = this.panelOf(panelId);
    const child = this.host.child(panelId);
    const ran = child?.getSnapshot().result?.config;
    // A group of expanded elements names an element's fields, which no
    // board filter narrows (`pressableGroups`): its press is the menu's.
    if (!found || !child || ran?.kind !== 'analysis' || ran.elements?.length)
      return null;
    const groups = drillGroups(ran, child.fields, this.host.kinds, row, {
      timeZone: child.environment.timeZone,
    });
    return groups ? { ...found, groups } : null;
  }

  /** The group's value in the filter's shape, or `null` for none. */
  private valueOf(
    panelId: string,
    filter: DashboardField,
    field: string,
    row: RecordData,
  ): FilterValue | null {
    const pressed = this.pressedOn(panelId, row);
    const type = filterTypeOf(filter.kind);
    const drilled = pressed?.groups.find(
      entry =>
        entry.group.field === field && takesGroup(type, entry.group.type),
    );
    return drilled ? filterValueOf(type, drilled) : null;
  }
}

/**
 * One dimension's value as the filter holds it (D22 I): a date bucket as
 * the window its conditions already are, a yes-or-no as itself, an id as
 * the one item a reference holds, text and numbers as a list of one — the
 * shape each filter type's operator takes (`filterOperatorOf`). The group
 * of records with no value is none: a filter cannot hold 「空」.
 */
function filterValueOf(
  type: ReturnType<typeof filterTypeOf>,
  drilled: DrilledGroup,
): FilterValue | null {
  const [condition] = drilled.conditions;
  if (!condition || condition.operator === 'IS_NULL') return null;
  const value = drilled.value;
  switch (type) {
    case 'date':
      return condition.value;
    case 'boolean':
      return typeof value === 'boolean' ? value : null;
    case 'id':
      return isReferenceFilterValue(condition.value)
        ? condition.value
        : {
            items: [{ id: value as string | number, label: String(value) }],
          };
    default:
      return typeof value === 'string' || typeof value === 'number'
        ? [value]
        : null;
  }
}

/**
 * Whether a date filter's value lies inside another's: a bucket inside the
 * span a brush set. Only two absolute windows compare; anything else is not
 * inside.
 */
function within(inner: FilterValue, outer: FilterValue | undefined): boolean {
  const a = absoluteWindow(inner);
  const b = absoluteWindow(outer);
  return a !== null && b !== null && a.from >= b.from && a.to <= b.to;
}

function absoluteWindow(
  value: FilterValue | undefined,
): { from: number; to: number } | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    return null;
  const window = value as { type?: unknown; from?: unknown; to?: unknown };
  if (
    window.type !== 'absolute' ||
    typeof window.from !== 'string' ||
    typeof window.to !== 'string'
  )
    return null;
  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  return Number.isNaN(from) || Number.isNaN(to) ? null : { from, to };
}

/**
 * Whether a condition on a field of the panel's view can be carried onto
 * another view: it has a field of that name and of the same filter type.
 */
function carriedOnto(
  source: readonly FieldDefinition[],
  target: readonly FieldDefinition[],
): (field: string) => boolean {
  const byName = new Map(target.map(field => [field.name, field]));
  return name => {
    const from = source.find(field => field.name === name);
    const to = byName.get(name);
    return !!from && !!to && sameFilterType(from.kind, to.kind);
  };
}

/**
 * The conjuncts of a tree another view can carry: each one whose every
 * field it has (`carries`); one that names a field it lacks is left out
 * whole rather than cut down to a question it never asked.
 */
function kept(
  tree: FilterTree | null,
  carries: (field: string) => boolean,
): FilterNode[] {
  if (!tree) return [];
  const conjuncts = tree.op === 'and' ? tree.children : [tree];
  return conjuncts.filter(node =>
    filterFields({ op: 'and', children: [node] }).every(carries),
  );
}

/** Conditions as one "all of" tree, or `null` for none. */
function tree(children: FilterNode[]): FilterTree | null {
  return children.length > 0 ? { op: 'and', children } : null;
}

/**
 * What a URL template's `{{field}}` takes from the group pressed, by the
 * field each dimension buckets: a date bucket as the instant it starts, a
 * value as itself; the records with no value as nothing.
 */
function groupTexts(groups: readonly DrilledGroup[]): Record<string, string> {
  const texts: Record<string, string> = {};
  for (const { group, value, conditions } of groups) {
    if (conditions[0]?.operator === 'IS_NULL') continue;
    const instant =
      group.type === 'DATE_HISTOGRAM' ? readInstant(value)?.ms : undefined;
    texts[group.field] =
      instant === undefined ? String(value) : new Date(instant).toISOString();
  }
  return texts;
}
