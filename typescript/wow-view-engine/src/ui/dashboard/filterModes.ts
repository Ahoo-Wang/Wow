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

import type { DashboardFilters } from '../../model/index.js';
import type { HeldFilters } from '../../runtime/index.js';

/**
 * How an embedding page offers each of a board's filters (D22, the
 * embedding half): Metabase's three states, one per filter.
 *
 * - `adjustable` — on the bar, the reader's to change. What every filter is
 *   unless the page says otherwise, and all a workbench knows.
 * - `locked` — on the bar as what it is fixed to, with no control: the page
 *   set it (a customer page, to its customer) and the reader reads it.
 * - `hidden` — not on the bar at all; it still holds its value — the page's,
 *   or the board's default — and narrows the panels wired to it.
 *
 * Locked and hidden are both the page's: the runtime holds them
 * (`DashboardRuntime.holdFilters`), so no command of the reader's — a value,
 * 「清空」, a press that cross-filters — changes them. Neither is a security
 * boundary: the condition is put together in the browser, and what a reader
 * may see is the Wow backend's to enforce.
 */
export type DashboardFilterMode = 'adjustable' | 'hidden' | 'locked';

/** The modes a page gives a board's filters, and its time grouping. */
export interface BoardFilterModes {
  /** By filter name; a filter not named is `adjustable`. */
  filters?: Readonly<Record<string, DashboardFilterMode>>;
  /** The time grouping's (按日｜周｜月); `adjustable` when left out. */
  grouping?: DashboardFilterMode;
}

/** One filter's mode: `adjustable` unless the page named it. */
export function filterModeOf(
  modes: BoardFilterModes | undefined,
  name: string,
): DashboardFilterMode {
  return modes?.filters?.[name] ?? 'adjustable';
}

/** The filters the page holds — locked or hidden — by name. */
export function heldFilters(modes: BoardFilterModes | undefined): string[] {
  return Object.entries(modes?.filters ?? {}).flatMap(([name, mode]) =>
    mode === 'adjustable' ? [] : [name],
  );
}

/** Whether the page holds the time grouping. */
export function holdsGrouping(modes: BoardFilterModes | undefined): boolean {
  return (modes?.grouping ?? 'adjustable') !== 'adjustable';
}

/**
 * What the page holds (`EmbeddedDashboard.pageValues`): each locked or
 * hidden filter at the value the page names — its default where it names
 * none — and, when the time grouping is held, the unit likewise; `null` for
 * a page that holds nothing. Entries for a filter the reader holds are no
 * part of it.
 */
export function heldOf(
  modes: BoardFilterModes | undefined,
  page: DashboardFilters | null | undefined,
): HeldFilters | null {
  const names = heldFilters(modes);
  const grouping = holdsGrouping(modes);
  if (names.length === 0 && !grouping) return null;
  const given = page?.values ?? {};
  return {
    values: Object.fromEntries(names.map(name => [name, given[name] ?? null])),
    ...(grouping ? { unit: page?.unit ?? null } : {}),
  };
}

/**
 * What of the filters is the reader's (`EmbeddedDashboard.initialFilters`,
 * `onFiltersChange`): the adjustable filters, and the time grouping unless the
 * page holds it. A locked or hidden filter's value never travels as the
 * reader's — not in from the address, where a reader could edit it, and not
 * out to it, where it would be read back as theirs.
 */
export function readersOf(
  modes: BoardFilterModes | undefined,
  filters: DashboardFilters,
): DashboardFilters {
  const held = new Set(heldFilters(modes));
  const values = Object.fromEntries(
    Object.entries(filters.values ?? {}).filter(([name]) => !held.has(name)),
  );
  const from = Object.entries(filters.from ?? {}).filter(
    ([name]) => !held.has(name),
  );
  const unit = holdsGrouping(modes) ? undefined : filters.unit;
  return {
    values,
    ...(from.length > 0 ? { from: Object.fromEntries(from) } : {}),
    ...(unit === undefined ? {} : { unit }),
  };
}

/**
 * The modes a static embed's bar reads (D36): the reader changes nothing
 * there, so every filter the page left adjustable — and the time grouping —
 * reads as what it holds, as a locked one does; a hidden one stays off the
 * bar. The bar's reading alone: what the runtime holds, and what the host's
 * address is told, are still the page's own modes.
 */
export function staticModes(
  fields: readonly { name: string }[],
  modes: BoardFilterModes | undefined,
): BoardFilterModes {
  const fixed = (mode: DashboardFilterMode): DashboardFilterMode =>
    mode === 'hidden' ? 'hidden' : 'locked';
  return {
    filters: Object.fromEntries(
      fields.map(({ name }) => [name, fixed(filterModeOf(modes, name))]),
    ),
    grouping: fixed(modes?.grouping ?? 'adjustable'),
  };
}
