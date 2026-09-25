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

import {
  filterTypeOf,
  type AnalysisDateUnit,
  type DashboardFilters,
  type DashboardViewConfig,
  type DataViewConfig,
  type FilterLeaf,
  type FilterNode,
  type FilterTree,
  type PanelBinding,
} from '../../model/index.js';
import {
  filterFields,
  isDateTimeFilterValue,
  isFilterGroup,
  isFilterLeaf,
  periodOf,
  resolveDateTimeBound,
  resolveDateTimeRange,
  type RelativeDateUnit,
} from '../../filter/index.js';
import { filtersOf } from '../../dashboard/index.js';

/** The moment and the zone a relative date is read at. */
export interface AnchorClock {
  now: Date;
  timeZone: string;
}

/**
 * A trend card anchored to the day a board's date filter picked (D39): the
 * config its child runs — its own relative dates read as of that day — and,
 * in place of the filter's own condition, the window that ends with it.
 */
export interface PanelAnchor {
  /** The binding whose condition the window stands in for. */
  binding: PanelBinding;
  /** The card's config, its own relative dates on the axis read at the anchor. */
  config: DataViewConfig;
  /** The window, on the panel's own field: `BETWEEN` two instants. */
  leaf: FilterLeaf;
}

/**
 * Where a trend card on a board ends its window (D39).
 *
 * A board's date filter narrows each panel wired to it. Narrowed to one
 * day, a card that reads 「较前一日」 and draws the last 30 days has one
 * bucket: nothing to compare with, no line. So a metric card with a trend
 * read as its last period (`MetricTrend.headline` `last`), whose time axis
 * is wired to a date filter holding exactly one period of the axis's unit
 * — 「昨日」 on a card by the day, 「上月」 on one by the month, as
 * `periodOf` reads it — is anchored to that period instead: its window
 * ends with it, and the headline is it, compared with the one before.
 *
 * How far back the window reaches is the card's own to say, the way it says
 * it anywhere else: a condition of its own on that field (「近 30 天」),
 * read as of the anchored period's last moment, so 「近 30 天」 on a board
 * set to 9 月 21 日 is 8 月 23 日 to 9 月 21 日. A card with no condition of
 * its own on the field reaches back one period, which is what 「较前一日」
 * needs. Every other panel wired to the filter is narrowed as before.
 *
 * `null` when the panel is not such a card, when no date filter wired to
 * its axis holds a value, when two do, or when the value is no one period.
 */
export function panelAnchor(
  config: DataViewConfig,
  bindings: readonly PanelBinding[],
  board: { applied: DashboardViewConfig; filters: DashboardFilters },
  clock: AnchorClock,
): PanelAnchor | null {
  const axis = trendAxis(config);
  if (!axis) return null;
  const dates = new Set(
    filtersOf(board.applied)
      .filter(field => filterTypeOf(field.kind) === 'date')
      .map(field => field.name),
  );
  const held = bindings.filter(
    binding =>
      binding.panelField === axis.field &&
      dates.has(binding.globalField) &&
      isDateTimeFilterValue(board.filters.values[binding.globalField]),
  );
  if (held.length !== 1) return null;
  const [binding] = held;
  const value = board.filters.values[binding.globalField];
  if (!isDateTimeFilterValue(value)) return null;
  const { from: start, to: end } = resolveDateTimeRange(
    value,
    clock.now,
    clock.timeZone,
  );
  if (end === undefined || periodOf(start, end, clock.timeZone) !== axis.unit)
    return null;
  const period = { from: start, to: end };

  const at = new Date(end);
  const own = filterFields(config.filter).includes(axis.field);
  const filter = own
    ? readAt(config.filter, axis.field, at, clock.timeZone)
    : config.filter;
  const from =
    (own ? earliest(filter, axis.field, at, clock.timeZone) : undefined) ??
    periodBefore(period, axis.unit, clock.timeZone);
  return {
    binding,
    config: filter === config.filter ? config : { ...config, filter },
    leaf: {
      field: axis.field,
      operator: 'BETWEEN',
      value: {
        type: 'absolute',
        from,
        to: period.to,
      },
    },
  };
}

/** A clock unit's length; a calendar unit steps the zone's calendar. */
const CLOCK_MS: Partial<Record<AnalysisDateUnit, number>> = {
  SECOND: 1000,
  MINUTE: 60_000,
  HOUR: 3_600_000,
};

/** Each calendar unit as a relative window counts it. */
const CALENDAR: Partial<Record<AnalysisDateUnit, RelativeDateUnit>> = {
  DAY: 'day',
  WEEK: 'week',
  MONTH: 'month',
  QUARTER: 'quarter',
  YEAR: 'year',
};

/**
 * The first moment of the period right before `period`: the start of 「近
 * 2 期」 read at its last moment — a relative window of days or longer is
 * whole days (D39), so two months read at 8 月 31 日 start on 7 月 1 日 —
 * and a clock unit's length before it.
 */
function periodBefore(
  period: { from: string; to: string },
  unit: AnalysisDateUnit,
  timeZone: string,
): string {
  const clock = CLOCK_MS[unit];
  const calendar = CALENDAR[unit];
  if (clock !== undefined || calendar === undefined)
    return new Date(Date.parse(period.from) - (clock ?? 0)).toISOString();
  return resolveDateTimeRange(
    { type: 'relative', amount: 2, unit: calendar },
    new Date(period.to),
    timeZone,
  ).from;
}

/** A metric card's trend axis read as its last period: the field and the unit. */
function trendAxis(
  config: DataViewConfig,
): { field: string; unit: AnalysisDateUnit } | null {
  if (config.kind !== 'analysis' || config.chart.type !== 'metric') return null;
  const trend = config.chart.metric?.trend;
  if (!trend || trend.headline === 'whole') return null;
  const group = config.groups.find(
    entry => entry.alias === trend.x && entry.type === 'DATE_HISTOGRAM',
  );
  return group?.type === 'DATE_HISTOGRAM'
    ? { field: group.field, unit: group.unit }
    : null;
}

/**
 * `tree` with every relative or named date on `field` read at `at` and
 * written as the two instants (or the one) it names there; the rest as it
 * was.
 */
function readAt(
  tree: FilterTree,
  field: string,
  at: Date,
  timeZone: string,
): FilterTree {
  const visit = (node: FilterNode): FilterNode => {
    if (isFilterGroup(node))
      return { ...node, children: node.children.map(visit) };
    if (!isFilterLeaf(node) || node.field !== field) return node;
    const value = node.value;
    if (!isDateTimeFilterValue(value) || value.type === 'absolute') return node;
    if (node.operator === 'BETWEEN') {
      const range = resolveDateTimeRange(value, at, timeZone);
      return { ...node, value: { type: 'absolute', ...range } };
    }
    if (node.operator !== 'GTE' && node.operator !== 'LTE') return node;
    const edge = node.operator === 'GTE' ? 'start' : 'end';
    return {
      ...node,
      value: {
        type: 'absolute',
        from: resolveDateTimeBound(value, at, timeZone, edge),
      },
    };
  };
  return { ...tree, children: tree.children.map(visit) };
}

/**
 * The lower bound a card's own conditions put on `field` when they all hold
 * — its top-level `BETWEEN`s and `GTE`s, read as instants — or `undefined`
 * when none bounds it from below at the top.
 */
function earliest(
  tree: FilterTree,
  field: string,
  at: Date,
  timeZone: string,
): string | undefined {
  if (tree.op !== 'and') return undefined;
  let from: number | undefined;
  for (const node of tree.children) {
    if (!isFilterLeaf(node) || node.field !== field) continue;
    if (node.operator !== 'BETWEEN' && node.operator !== 'GTE') continue;
    const value = node.value;
    if (!isDateTimeFilterValue(value)) continue;
    const start = Date.parse(resolveDateTimeRange(value, at, timeZone).from);
    if (!Number.isNaN(start) && (from === undefined || start > from))
      from = start;
  }
  return from === undefined ? undefined : new Date(from).toISOString();
}
