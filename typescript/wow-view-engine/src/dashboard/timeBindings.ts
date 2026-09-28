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
 * A board's time filter reaching its panels on its own (todo D, D67): every
 * panel over data that says when its records happen
 * (`DataViewDefinition.timeField`, `SystemView.timeField`) is narrowed by
 * the board's time range without a wire written for it. A wire written by
 * hand always wins, and a panel that reads whole says so
 * (`DashboardViewPanel.ignoresTime`).
 *
 * **Derived, never stored** (D1, user 2026-09-28). The wires are read into
 * the board, marked as derived (`derived: true`, with `auto`), where a board
 * is read under its panels' definitions (`canonicalBoard`) — so every part
 * of the engine sees one board — and taken out again when it is saved
 * (`storedTimeWires`): what persists is the wires written by hand or
 * auto-connected (D22 G) and `ignoresTime`. Only a derived wire is ever
 * made again: one auto-connect made to a date filter is the author's, and
 * keeps its field when the board is down to that one date filter.
 *
 * A board stored before this carries no marker (`DashboardViewConfig
 * .derivesTime`), and was read with every unwired panel unwired: its
 * migration (`withTimeIgnored`) says so of each such panel, so its numbers
 * stay what they were.
 */

import {
  filterTypeOf,
  sameFilterType,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type FieldDefinition,
  type PanelBinding,
} from '../model/index.js';
import { isPlainObject } from '../filter/index.js';
import { filtersOf } from './filters.js';
import { bindingsOf, isViewPanel, panelsOf } from './panels.js';

/**
 * The board's time filter: its one date filter. A board with two — when an
 * order was placed, when it shipped — has no one range to read every panel
 * in, so none is wired on its own there.
 */
export function timeFilterOf(
  config: DashboardViewConfig,
): DashboardField | null {
  const dated = dateFilters(config);
  return dated.length === 1 ? dated[0] : null;
}

/** Whether a filter is one of dates. */
export function isDateFilter(filter: DashboardField | undefined): boolean {
  return filter !== undefined && filterTypeOf(filter.kind) === 'date';
}

function dateFilters(config: DashboardViewConfig): DashboardField[] {
  return filtersOf(config).filter(isDateFilter);
}

/**
 * What a panel's view says of time: the field its records happen at,
 * `null` for a view read whole, `undefined` where it says nothing — a view
 * not known yet, or a definition that declares no time field, whose wires
 * are all the author's.
 */
export type PanelTime = (
  panel: DashboardViewPanel,
) => FieldDefinition | null | undefined;

/**
 * The board with its time filter wired to every data panel it reaches on
 * its own through `timeOf` (`auto` and `derived`), and every wire derived
 * before that no longer follows taken off: one whose view says otherwise
 * now or is not known, one on a panel that reads whole, and every one while
 * the board has no single date filter. A wire auto-connect made is the
 * author's and is never touched. Without the storage marker; itself when
 * nothing changes — a panel whose wires come out as they were is the same
 * panel.
 */
export function withTimeBindings(
  config: DashboardViewConfig,
  timeOf: PanelTime,
): DashboardViewConfig {
  const filter = timeFilterOf(config);
  let changed = false;
  const panels = panelsOf(config).map(panel => {
    if (!isViewPanel(panel)) return panel;
    const stored = storedBindings(panel);
    const kept = stored.filter(entry => !isDerived(entry));
    const time = filter && panel.ignoresTime !== true ? timeOf(panel) : null;
    const written =
      filter &&
      kept.some(
        entry => isPlainObject(entry) && entry.globalField === filter.name,
      );
    const wire =
      filter && time && !written && sameFilterType(filter.kind, time.kind)
        ? [
            {
              globalField: filter.name,
              panelField: time.name,
              auto: true,
              derived: true,
            },
          ]
        : [];
    const next = [...kept, ...wire];
    if (sameWires(next, stored)) return panel;
    changed = true;
    return { ...panel, bindings: next as PanelBinding[] };
  });
  const marked = 'derivesTime' in config;
  if (!changed && !marked) return config;
  const next = { ...config, panels: changed ? panels : config.panels };
  delete next.derivesTime;
  return next;
}

/**
 * The board as it is stored: every wire derived from a view taken off —
 * it is made again when read — and the marker that says so.
 */
export function storedTimeWires(
  config: DashboardViewConfig,
): DashboardViewConfig {
  const panels = panelsOf(config).map(panel => {
    if (!isViewPanel(panel)) return panel;
    const stored = storedBindings(panel);
    const kept = stored.filter(entry => !isDerived(entry));
    return kept.length === stored.length
      ? panel
      : { ...panel, bindings: kept as PanelBinding[] };
  });
  return { ...config, panels, derivesTime: true };
}

/**
 * A board stored before its time wires were derived, read as it was then
 * (D1): unmarked, so each panel over data not wired to every one of its
 * date filters reads whole (`ignoresTime`) — nothing wires it on its own,
 * whichever of those filters is left the board's one. Its wires are the
 * author's, auto-connected ones included, and stay. Marked, so it happens
 * once; a marked board as it is.
 */
export function withTimeIgnored(
  config: DashboardViewConfig,
): DashboardViewConfig {
  const stored: unknown = config;
  if (!isPlainObject(stored) || stored.derivesTime === true) return config;
  const dated = dateFilters(config).map(filter => filter.name);
  const panels = panelsOf(config).map((panel): DashboardPanel => {
    if (dated.length === 0 || !isViewPanel(panel)) return panel;
    const wired = new Set(bindingsOf(panel).map(entry => entry.globalField));
    return dated.every(name => wired.has(name))
      ? panel
      : { ...panel, ignoresTime: true };
  });
  return {
    ...config,
    ...(Array.isArray(stored.panels) ? { panels } : {}),
    derivesTime: true,
  };
}

/** A panel's stored wires, whatever they hold, for admission to report on. */
function storedBindings(panel: DashboardViewPanel): unknown[] {
  const stored: unknown = panel.bindings;
  return Array.isArray(stored) ? stored : [];
}

/** Whether a wire is one derived from a view's time field. */
function isDerived(entry: unknown): boolean {
  return isPlainObject(entry) && entry.derived === true;
}

/** Whether two lists of wires say the same, entry by entry. */
function sameWires(
  one: readonly unknown[],
  other: readonly unknown[],
): boolean {
  return (
    one.length === other.length &&
    one.every(
      (entry, index) =>
        entry === other[index] ||
        JSON.stringify(entry) === JSON.stringify(other[index]),
    )
  );
}
