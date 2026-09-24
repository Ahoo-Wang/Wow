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
  type FilterLeaf,
  type FilterValue,
  type Issue,
  type IssuePath,
  type RecordData,
} from '../../model/index.js';
import {
  isReferenceFilterValue,
  issue,
  readInstant,
  type FieldKindRegistry,
} from '../../filter/index.js';
import { drillGroups, type DrilledGroup } from '../../analysis/index.js';
import {
  bindingsOf,
  clickOf,
  defaultFilters,
  fillUrl,
  filtersOf,
  isViewPanel,
  migrateDashboardConfig,
  takesGroup,
  validateBoardClick,
  type BoardClick,
  type PanelReference,
} from '../../dashboard/index.js';
import type { DataViewRuntime } from '../viewRuntime.js';
import type { DashboardNavigation } from './contract.js';
import { panelsOf } from './panels.js';

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
  | { to: DashboardNavigation }
  | { refused: Issue }
  /**
   * The click cannot do what it says — a board it opens is gone, or a
   * filter or a dimension it maps is (D23 Q17): the follow-up menu opens on
   * the group instead, as it does for a click admission warned of, and
   * `issue` says why. The board read by the press re-judges the panel, so
   * the warning stays on it and the next press opens the menu directly.
   */
  | { fallback: Issue };

/** Another board as a click reads it: the instance, its config migrated. */
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
  /** A saved view, loaded: what a destination carries its group into. */
  reference(instanceId: string): Promise<PanelReference | null>;
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
   * Whether the filter the panel's press sets holds this group's value, set
   * by a press on this panel: the group the panel marks, since it is not
   * narrowed by the value it set.
   */
  pressed(panelId: string, row: RecordData): boolean {
    const target = this.filterOf(panelId);
    if (!target) return false;
    const current = this.host.filters();
    if (current.from?.[target.filter.name] !== panelId) return false;
    const value = this.valueOf(panelId, target.filter, target.field, row);
    return value !== null && dequal(current.values[target.filter.name], value);
  }

  /**
   * Where a press on a panel with a custom destination goes: a page of the
   * host's, its URL filled with the group; or another saved view, under the
   * group's conditions on the fields its data has too — by name and by type,
   * the rule auto-connect wires by (D22 G). A view that is gone, or is a
   * dashboard, is refused and said.
   */
  async destination(
    panelId: string,
    row: RecordData,
  ): Promise<PressDestination | null> {
    const pressed = this.pressedOn(panelId, row);
    const click = pressed && clickOf(pressed.panel);
    if (!pressed || !click || click.kind === 'filter') return null;
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
    const byName = new Map(reference.fields.map(field => [field.name, field]));
    const child = this.host.child(panelId);
    const conditions: FilterLeaf[] = pressed.groups.flatMap(drilled => {
      const source = child?.fields.find(
        field => field.name === drilled.group.field,
      );
      const target = byName.get(drilled.group.field);
      return source && target && sameFilterType(source.kind, target.kind)
        ? drilled.conditions
        : [];
    });
    return {
      to: {
        kind: 'view',
        instanceId: click.instanceId,
        filter:
          conditions.length > 0 ? { op: 'and', children: conditions } : null,
      },
    };
  }

  /**
   * Another board a click opens, read for 「点击时…」 or a press — never
   * when this board opens — through the references the panels load, so
   * the read re-judges the click (`validateBoardClick`); `null` for one
   * gone, unreadable or not a board.
   */
  async board(instanceId: string): Promise<DestinationBoard | null> {
    const reference = await this.host.reference(instanceId);
    const config = reference?.instance.config;
    return reference && config?.kind === 'dashboard'
      ? { ...reference.instance, config: migrateDashboardConfig(config) }
      : null;
  }

  /**
   * A press that opens another board (D23 Q17): the board read, the
   * mapping judged against it and the panel, then each filter mapped set
   * from the group's value on its dimension, in the filter's shape — the
   * rest at the target's defaults, as a board opened without a value for
   * them would start. A group without a value for a filter (the records
   * without one) leaves that filter at its default too.
   */
  private async toBoard(
    click: BoardClick,
    pressed: Pressed,
    at: IssuePath,
  ): Promise<PressDestination> {
    const reference = await this.host.reference(click.instanceId);
    const found = validateBoardClick(
      click,
      at,
      pressed.groups.map(drilled => drilled.group),
      {
        fields: this.host.child(pressed.panel.id)?.fields ?? null,
        target: reference,
      },
    );
    const stored = reference?.instance.config;
    if (found.length > 0 || !reference || stored?.kind !== 'dashboard')
      return { fallback: found[0] ?? issue('dashboard.click.board-gone', at) };
    // Read as the board opens: a pre-C condition is its filters' defaults.
    const config = migrateDashboardConfig(stored);
    const filters = defaultFilters(config);
    const byName = new Map(filtersOf(config).map(field => [field.name, field]));
    for (const [name, field] of Object.entries(click.values)) {
      const filter = byName.get(name);
      const type = filterTypeOf(filter?.kind);
      const drilled = pressed.groups.find(
        entry =>
          entry.group.field === field && takesGroup(type, entry.group.type),
      );
      const value = drilled ? filterValueOf(type, drilled) : null;
      if (value !== null) filters.values[name] = value;
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

  /** The filter a panel's press sets, and the field it is wired through. */
  private filterOf(
    panelId: string,
  ): { filter: DashboardField; field: string } | null {
    const panel = this.panelOf(panelId)?.panel;
    const click = panel && clickOf(panel);
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
    if (!found || !child || ran?.kind !== 'analysis') return null;
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
