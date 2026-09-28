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
 * the board, marked as made (`auto: true`), where a board is read under its
 * panels' definitions (`canonicalBoard`) — so every part of the engine sees
 * one board — and taken out again when it is saved (`storedTimeWires`):
 * what persists is the wires written by hand and `ignoresTime`. A wire
 * derived from a view that no longer says so, or from a panel's previous
 * view, is never read back.
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
 * its own through `timeOf`, and every wire made so before (`auto: true`)
 * that no longer holds taken off: one whose view says otherwise now, one on
 * a panel that reads whole. Without the storage marker; itself when nothing
 * changes.
 */
export function withTimeBindings(
  config: DashboardViewConfig,
  timeOf: PanelTime,
): DashboardViewConfig {
  const filter = timeFilterOf(config);
  let changed = false;
  const panels = panelsOf(config).map(panel => {
    if (!filter || !isViewPanel(panel)) return panel;
    const time = timeOf(panel);
    const ignores = panel.ignoresTime === true;
    const stored = storedBindings(panel);
    // Made from the view, so remade from it: kept only where nothing else
    // says what the panel's time is.
    const kept =
      time === undefined && !ignores
        ? stored
        : stored.filter(entry => !madeFor(entry, filter.name));
    const written = kept.some(
      entry => isPlainObject(entry) && entry.globalField === filter.name,
    );
    const wire =
      !ignores && !written && time && sameFilterType(filter.kind, time.kind)
        ? [{ globalField: filter.name, panelField: time.name, auto: true }]
        : [];
    if (kept.length === stored.length && wire.length === 0) return panel;
    changed = true;
    return { ...panel, bindings: [...kept, ...wire] as PanelBinding[] };
  });
  const marked = 'derivesTime' in config;
  if (!changed && !marked) return config;
  const next = { ...config, panels: changed ? panels : config.panels };
  delete next.derivesTime;
  return next;
}

/**
 * The board as it is stored: every wire made from a view (`auto: true`)
 * to its time filter taken off a panel whose time `timeOf` knows or that
 * reads whole — it is made again when read — and the marker that says so.
 */
export function storedTimeWires(
  config: DashboardViewConfig,
  timeOf: PanelTime,
): DashboardViewConfig {
  const filter = timeFilterOf(config);
  const panels = panelsOf(config).map(panel => {
    if (!filter || !isViewPanel(panel)) return panel;
    if (timeOf(panel) === undefined && panel.ignoresTime !== true) return panel;
    const stored = storedBindings(panel);
    const kept = stored.filter(entry => !madeFor(entry, filter.name));
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
 * whichever of those filters is left the board's one — and each wire
 * auto-connect made (`auto: true`) to a date filter is kept as though
 * written, since it is not the view's to remake. Marked, so it happens once;
 * a marked board as it is.
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
    const bindings = storedBindings(panel).map(entry =>
      isPlainObject(entry) &&
      entry.auto === true &&
      typeof entry.globalField === 'string' &&
      dated.includes(entry.globalField)
        ? { globalField: entry.globalField, panelField: entry.panelField }
        : entry,
    ) as PanelBinding[];
    return dated.every(name => wired.has(name))
      ? { ...panel, bindings }
      : { ...panel, bindings, ignoresTime: true };
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

/** Whether a stored wire is one made to the time filter. */
function madeFor(entry: unknown, filter: string): boolean {
  return (
    isPlainObject(entry) && entry.auto === true && entry.globalField === filter
  );
}
