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
 * Admission and scheduling budgets. Configs arrive from a store, so the
 * validators apply these before anything reaches a compiler or a timer.
 */
export interface RuntimeLimits {
  /** Data queries running at once across every runtime of one engine. */
  maxConcurrentQueries: number;
  /** Queued data queries; beyond this a request is rejected, not buffered. */
  maxQueuedQueries: number;
  /** Upper bound of `RecordViewConfig.pageSize`. */
  maxPageSize: number;
  /**
   * The most rows a paged query may reach, page × size, whatever the
   * definition says: a definition's `RecordCapability.maxWindow` may only
   * lower it. The pager stops at the last page inside it and an export
   * stops there too (`pageWindow`).
   */
  maxPageWindow: number;
  /**
   * The most rows any analysis query asks for: the upper bound of
   * `AnalysisViewConfig.limit`, and of what the engine asks beside it — the
   * probe row that tells a cut result from a whole one, and the split's
   * whole a 「其他」 is measured against. A definition's `maxLimit` may
   * only lower it (`limitBounds`).
   */
  maxAnalysisRows: number;
  /**
   * Rows one export may carry away, however many the conditions match.
   *
   * An export pages the source under the applied conditions with no regard
   * for the page on screen, so it is the one command here whose cost is the
   * size of the *result* rather than of a page. The ceiling is what keeps a
   * mis-aimed export from asking a backend for a million rows and building a
   * string of them in a browser tab; over it, the count is put to the user
   * before anything is fetched.
   */
  exportMax: number;
  /** Shortest auto-refresh interval, in seconds. */
  minRefreshInterval: number;
  /** Longest auto-refresh interval, in seconds; keeps the timer in range. */
  maxRefreshInterval: number;
  /** Nesting depth of a filter tree, checked iteratively before recursion. */
  maxFilterDepth: number;
  /**
   * Total nodes of a filter tree as a config holds it, groups included; and
   * of an expression or 「只保留」 tree. The engine's own guard on a tree
   * that arrives from a store, walked before anything recurses into it —
   * not what a source admits, which `maxQueryFilterNodes` says.
   */
  maxFilterNodes: number;
  /**
   * The most filter nodes one query may send, counted as a Wow service's
   * query guard counts them (`max-filter-nodes`): on the compiled query,
   * its own filter, each element's and each metric's, and its 「只保留」
   * together — a date range is one condition and three nodes. A source's
   * descriptor says it (`limits.maxFilterNodes`); the host only lowers it.
   */
  maxQueryFilterNodes: number;
  /**
   * The most values one filter node may carry — an `IN` list, `IDS`, a
   * 「只保留」 `IN` — as the guard counts them (`max-filter-values`). A
   * source's descriptor says it (`limits.maxFilterValues`).
   */
  maxFilterValues: number;
  /** Panels of one dashboard, checked before any child runtime is created. */
  maxDashboardPanels: number;

  // What the controls offer and what a new view starts from. These are the
  // product's presentation choices rather than budgets — a ladder of page
  // sizes, a ladder of refresh cadences, how many columns a first view
  // shows — and they used to be module constants that a host could only
  // change by shipping a build. They sit beside the budgets because the
  // budgets cut them (a rung above `maxPageSize` is never offered), and
  // because one object handed to the engine is one place to look.

  /**
   * The page sizes a control offers from, before `maxPageSize` cuts them and
   * the size a view is saved at is folded in.
   */
  pageSizes: readonly number[];
  /**
   * The page sizes the card layout offers from, cut and folded the same way.
   *
   * A page of cards is read in rows, and a page that is not a whole number
   * of rows ends on a row with a gap in it — 50 cards three to a row is
   * sixteen rows and a stray two (the user's 2026-09-23 review). The ladder
   * is multiples of 12 because 12 is a whole number of rows at every width a
   * grid can be: one to four cards per row, and the fewer columns a narrow
   * screen falls back to. Switching the layout moves the page size to the
   * nearest rung of the other ladder (`nearestPageSize`), so 20 rows of a
   * table come back as 24 cards and go back as 20 rows.
   */
  cardPageSizes: readonly number[];
  /**
   * The auto-refresh cadences a control offers from, in seconds, before the
   * two interval bounds cut them and the interval in force is folded in.
   *
   * Three rungs by default, and deliberately few. Half a minute is the
   * shortest cadence a person reads as "live" without the screen redrawing
   * under their hands, five minutes the longest before "keeps itself up to
   * date" stops being the reason anyone opened this view; a quarter of an
   * hour and an hour were rungs nobody picked and everybody had to read
   * past. Every rung divides into whole seconds or minutes, so each has a
   * label nobody has to decode — and a view already saved at some other
   * number keeps its own rung, so shortening the ladder strands nobody.
   */
  refreshIntervals: readonly number[];
  /** The page size a new record view starts at, unless its definition says. */
  defaultPageSize: number;
  /** Columns a new record view shows; more is noise in a first view. */
  defaultColumns: number;
  /** Fields on a new record view's cards, the title aside. */
  defaultCardFields: number;
  /**
   * Whether every file the default UI exports — a record view's rows and an
   * analysis's groups — is written with its formulas neutralized: a cell a
   * spreadsheet could evaluate gets a leading apostrophe
   * (`CsvOptions.neutralizeFormulas`, OWASP CSV Injection). On by default,
   * and on wherever it is left out; `false` is the host's explicit opt-out,
   * for files that never reach a spreadsheet.
   */
  exportNeutralizeFormulas: boolean;
}

export const DEFAULT_RUNTIME_LIMITS: Readonly<RuntimeLimits> = Object.freeze({
  maxConcurrentQueries: 4,
  maxQueuedQueries: 32,
  // The source budgets are a Wow server's HTTP query guard left at its
  // defaults (`wow.query.http.*`, checked at gateway admission; D42): the engine
  // asks for no more than such a server admits, and a host that raises the
  // server's guard raises these with it. Above them a server refuses the
  // query — every export failed when `maxPageSize` was 200, and the split's
  // 「其他」 was never folded when `maxAnalysisRows` was 10,000.
  // The guard refuses a page of more than 100 rows (its `maxPageSize`) …
  maxPageSize: 100,
  // … a page reaching past row 10,000 (`maxPageWindow`) …
  maxPageWindow: 10_000,
  // … an aggregation of more than 1,000 rows (`maxListSize`) …
  maxAnalysisRows: 1_000,
  // … a query sending more than 128 filter nodes (`max-filter-nodes`) …
  maxQueryFilterNodes: 128,
  // … and a node holding more than 1,000 values (`max-filter-values`).
  maxFilterValues: 1_000,
  exportMax: 10_000,
  minRefreshInterval: 5,
  maxRefreshInterval: 86_400,
  maxFilterDepth: 8,
  maxFilterNodes: 256,
  maxDashboardPanels: 24,
  pageSizes: Object.freeze([10, 20, 50, 100]),
  cardPageSizes: Object.freeze([12, 24, 48, 96]),
  refreshIntervals: Object.freeze([30, 60, 300]),
  defaultPageSize: 20,
  defaultColumns: 8,
  defaultCardFields: 4,
  exportNeutralizeFormulas: true,
});

/**
 * Longest timer delay a 32-bit millisecond counter holds. `maxRefreshInterval`
 * stays well below it, so a long interval never collapses into a tight loop.
 */
export const MAX_TIMER_DELAY_MS = 2_147_483_647;

/**
 * The rung of a ladder nearest a page size, for a layout switch that moves
 * a view from one ladder to the other (`RuntimeLimits.cardPageSizes`). A
 * tie goes to the larger rung, so a switch never takes rows away where it
 * could give one more; a size already on the ladder, or an empty ladder,
 * leaves the size as it is.
 */
export function nearestPageSize(
  ladder: readonly number[],
  size: number,
): number {
  if (ladder.length === 0 || ladder.includes(size)) return size;
  return ladder.reduce((best, rung) => {
    const gap = Math.abs(rung - size);
    const bestGap = Math.abs(best - size);
    return gap < bestGap || (gap === bestGap && rung > best) ? rung : best;
  });
}
