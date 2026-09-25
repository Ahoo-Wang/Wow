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

import type { CursorQuery, FilterPagedQuery } from '@ahoo-wang/wow-client';
import {
  FIRST_PAGE,
  compileRecord,
  lastPageInWindow,
  pageWindow,
} from '../record/index.js';
import type { FilterCompileContext } from '../filter/index.js';
import {
  DEFAULT_RUNTIME_LIMITS,
  type RecordCapability,
  type RecordData,
  type RecordPageTarget,
  type RecordViewConfig,
  type RuntimeLimits,
} from '../model/index.js';
import type { KernelContext } from './execute.js';

export interface ExportRowsOptions {
  /** Stops the run; the promise then rejects with an `ExportCancelled`. */
  signal?: AbortSignal;
  /**
   * How far it has got: rows in hand, and how many there are in all when the
   * source says. A cursor source never says, which is why the total is
   * optional rather than a number nobody can compute.
   */
  onProgress?(fetched: number, total?: number): void;
  /** The ceiling; `limits.exportMax` when left out. */
  max?: number;
}

export interface ExportedRows {
  rows: RecordData[];
  /** What the source said there are in all, when it says at all. */
  total?: number;
  /** True when the ceiling stopped it while the source still had rows. */
  capped: boolean;
}

/**
 * What a cancelled export rejects with. It is not a failure — nobody has to
 * be told about it — so the caller tells it apart rather than reporting it.
 */
export class ExportCancelled extends Error {
  constructor() {
    super('The export was cancelled');
    this.name = 'ExportCancelled';
  }
}

export function isExportCancelled(error: unknown): error is ExportCancelled {
  return error instanceof ExportCancelled;
}

/**
 * How an export pages its source: the size it asks for, and the most rows it
 * may carry away.
 *
 * The ceiling is `limits.exportMax`, and below it a paged source's window
 * (`pageWindow`: the runtime's `maxPageWindow`, or the definition's
 * `maxWindow` where it is the smaller): a page past the
 * window is refused outright, so an export that went on asking would fail on
 * the page after the last one it could have, having fetched every row before
 * it. The size shrinks to the window when the window is the smaller, and the
 * ceiling stops at the last whole page inside it — the same count the pager
 * stops at. `ExportDialog` states this ceiling before anything is fetched.
 */
export function exportPlan(
  limits: Pick<RuntimeLimits, 'exportMax' | 'maxPageSize'> &
    Partial<Pick<RuntimeLimits, 'maxPageWindow'>>,
  capability?: Partial<Pick<RecordCapability, 'maxWindow' | 'paging'>>,
  asked?: number,
): { size: number; max: number } {
  // The ceiling is the one number that ends the fetch loop, so a host that
  // built its limits by hand and left it out gets the default rather than a
  // `NaN` that compares false against everything and pages a backend forever.
  const wanted = asked ?? limits.exportMax;
  const max = Number.isFinite(wanted)
    ? Math.floor(wanted)
    : DEFAULT_RUNTIME_LIMITS.exportMax;
  const window = pageWindow(capability, limits);
  const size = Math.max(
    1,
    Math.min(
      Math.floor(limits.maxPageSize) || DEFAULT_RUNTIME_LIMITS.maxPageSize,
      window ?? Infinity,
    ),
  );
  const pages = lastPageInWindow(size, window);
  return { size, max: pages === undefined ? max : Math.min(max, pages * size) };
}

/**
 * Every row the applied conditions match, paged out of the source behind the
 * screen.
 *
 * It is a query of its own and deliberately beside the runtime's: it takes no
 * scheduler slot, never becomes `state.result` and cannot supersede — or be
 * superseded by — what the user is looking at. Export is a read the user asked
 * for *in addition to* the view, and a view that emptied itself because an
 * export was running would be the worse answer.
 *
 * The page size is the runtime's `maxPageSize` rather than the view's, since
 * the page on screen has nothing to do with it: the fewer round trips the
 * better, and that ceiling is exactly what the source was admitted for.
 *
 * `ctx.now` is read once, at the start, so a relative condition means the same
 * thing on the last page as on the first — twenty pages apart, "today" must
 * not roll over into tomorrow halfway through one file.
 */
export async function fetchExportRows(
  context: KernelContext,
  config: RecordViewConfig,
  options: ExportRowsOptions = {},
): Promise<ExportedRows> {
  const { definition, kinds, limits, environment } = context;
  const { size, max } = exportPlan(limits, definition.record, options.max);
  const filterContext: FilterCompileContext = {
    now: environment.now(),
    timeZone: environment.timeZone,
  };
  // The config as it ran, paged the export's way. Everything else — the
  // filter, the sort, the scope already merged into it — is left alone, which
  // is what makes the file the rows the screen was showing.
  const paged: RecordViewConfig = { ...config, pageSize: size };
  const cursored = definition.record?.paging === 'cursor';

  const rows: RecordData[] = [];
  let total: number | undefined;
  let target: RecordPageTarget = cursored
    ? FIRST_PAGE.cursor
    : FIRST_PAGE.paged;

  for (;;) {
    if (rows.length >= max) return done(rows, max, total, true);
    stopIfCancelled(options.signal);
    const query = compileRecord(
      definition,
      paged,
      kinds,
      filterContext,
      target,
    );
    const page = await fetchPage(context, query, target, size, options.signal);
    stopIfCancelled(options.signal);

    rows.push(...page.list);
    if (page.total !== undefined) total = page.total;
    options.onProgress?.(Math.min(rows.length, max), total);
    // A page with nothing in it is the end of the result whatever the source
    // said about a next one — and it is what keeps a source that answers
    // every request with an empty page from being asked forever.
    if (page.next === null || page.list.length === 0)
      return done(rows, max, total, false);
    target = page.next;
  }
}

/** One page, and where the next one is — `null` at the end of the result. */
async function fetchPage(
  context: KernelContext,
  query: FilterPagedQuery | CursorQuery,
  target: RecordPageTarget,
  size: number,
  signal: AbortSignal | undefined,
): Promise<{
  list: readonly RecordData[];
  total?: number;
  next: RecordPageTarget | null;
}> {
  // `ViewSource` cancels through a controller because that is what Wow's
  // `QueryApi` takes; the caller cancels through a signal because that is what
  // a component holds. One export's controllers all follow the one signal.
  const controller = new AbortController();
  const unlink = follow(signal, controller);
  try {
    if ('cursor' in target) {
      const page = await context.source.cursor(query, undefined, controller);
      return {
        list: page.list,
        next: page.nextCursor === null ? null : { cursor: page.nextCursor },
      };
    }
    const page = await context.source.paged(query, undefined, controller);
    // A short page is the last one. A full page may still be the last, and
    // the next request answers that; asking once more is cheaper than the
    // row a wrong guess would drop.
    return {
      list: page.list,
      ...(typeof page.total === 'number' ? { total: page.total } : {}),
      next: page.list.length < size ? null : { index: target.index + 1 },
    };
  } finally {
    unlink();
  }
}

/** The result, cut to the ceiling. */
function done(
  rows: RecordData[],
  max: number,
  total: number | undefined,
  capped: boolean,
): ExportedRows {
  return {
    rows: rows.length > max ? rows.slice(0, max) : rows,
    ...(total === undefined ? {} : { total }),
    capped: capped || rows.length > max,
  };
}

function stopIfCancelled(signal: AbortSignal | undefined): void {
  if (signal?.aborted) throw new ExportCancelled();
}

/** Aborts `controller` when `signal` does, and unsubscribes when it is over. */
function follow(
  signal: AbortSignal | undefined,
  controller: AbortController,
): () => void {
  if (!signal) return () => {};
  if (signal.aborted) {
    controller.abort();
    return () => {};
  }
  const onAbort = () => controller.abort();
  signal.addEventListener('abort', onAbort, { once: true });
  return () => signal.removeEventListener('abort', onAbort);
}
