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
 * Where a paged result stands, as the pager reads it: every number the bar
 * shows and every step it offers, worked out once from what **ran**.
 *
 * The size is the executed config's and never the draft's. While a new page
 * size is still on its way the rows on screen, their page number and the
 * total all belong to the old one, and a page count divided by the new one
 * describes a result nobody has seen.
 */
export interface PagedPaging {
  mode: 'paged';
  /** The page the rows came from, counted from 1. */
  index: number;
  /** The page size the rows were fetched at. */
  size: number;
  /** Every row the conditions match, when the source says. */
  total?: number;
  /**
   * The pages the pager can reach: all of them, or only those inside the
   * source's window (`RecordCapability.maxWindow`). Absent when the source
   * reported no total, since then there is nothing to count them from.
   */
  pages?: number;
  /** Whether a page after this one can be asked for at all. */
  hasNext: boolean;
  /**
   * The rows the reachable pages hold — present only when that is fewer than
   * the total, because the source's window stops the pager short of it.
   */
  reachable?: number;
}

/** A cursor result: the one way on, when there is one. */
export interface CursorPaging {
  mode: 'cursor';
  nextCursor: string | null;
  hasNext: boolean;
}

export type RecordPaging = PagedPaging | CursorPaging;

/**
 * The last page a source with this window serves at this size: the page `p`
 * with `p × size` still inside it. Never below 1 — the first page is asked
 * for whatever the window says, and a source that refuses it says so as a
 * failed query rather than as a pager with no pages.
 *
 * `undefined` when there is no window, or no size to divide it by.
 */
export function lastPageInWindow(
  size: number,
  maxWindow?: number,
): number | undefined {
  if (maxWindow === undefined || !(size > 0)) return undefined;
  return Math.max(1, Math.floor(maxWindow / size));
}

/**
 * The paging facts of one paged result.
 *
 * A page is reachable when `index × size` stays inside the window, which is
 * why the count is `⌊window / size⌋` rather than `⌈window / size⌉`: at 30 a
 * page, a 10 000-row window serves 333 full pages, and page 334 would reach
 * row 10 020. So a total the window does cover can still lose its last page
 * to it, and `reachable` says so whenever the pages stop short of the total,
 * not only when the total is larger than the window.
 */
export function pagedPaging(facts: {
  index: number;
  size: number;
  total?: number;
  maxWindow?: number;
}): PagedPaging {
  const { index, size, total, maxWindow } = facts;
  const last = lastPageInWindow(size, maxWindow);
  if (total === undefined || !(size > 0))
    return {
      mode: 'paged',
      index,
      size,
      ...(total === undefined ? {} : { total }),
      // Without a total nothing rules the next page out — except the window,
      // which is a bound on the request and not on the result.
      hasNext: last === undefined || index < last,
    };

  const all = Math.max(1, Math.ceil(total / size));
  const pages = last === undefined ? all : Math.min(all, last);
  const reach = pages * size;
  return {
    mode: 'paged',
    index,
    size,
    total,
    pages,
    hasNext: index < pages,
    ...(reach < total ? { reachable: reach } : {}),
  };
}

/** The cursor result's facts: there is a next page when there is a cursor. */
export function cursorPaging(nextCursor: string | null): CursorPaging {
  return { mode: 'cursor', nextCursor, hasNext: nextCursor !== null };
}

/**
 * A page the pager may ask for: at least 1, and no later than the last it
 * can reach — past the end, the reader wants the end. Where the pages are
 * not counted there is no end to hold it to.
 */
export function clampPage(paging: PagedPaging, index: number): number {
  return Math.max(1, Math.min(Math.floor(index), paging.pages ?? index));
}

/**
 * Where a page that came back empty belongs instead: the last page there
 * now is, when the rows it counted left the result under it — a refresh
 * after a command took rows out, on the last page of a shrinking result.
 * `null` when the page is where the reader belongs: it holds rows, it is
 * the first, or the pages are not counted.
 */
export function pageAfterShrink(
  paging: RecordPaging,
  index: number,
  rows: number,
): number | null {
  if (rows > 0 || index <= 1) return null;
  if (paging.mode !== 'paged' || paging.pages === undefined) return null;
  const last = Math.max(1, paging.pages);
  return last < index ? last : null;
}
