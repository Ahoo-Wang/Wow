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

import { useCallback, useEffect, useRef, useState } from 'react';
import { issue } from '../filter/index.js';
import type { Issue, RecordData } from '../model/index.js';
import { isExportCancelled, type RecordViewRuntime } from '../runtime/index.js';
import { toIssue } from './issues.js';
import type { RecordTableController } from './useRecordTable.js';

/** The three readings of "export": what is picked, what is shown, what matches. */
export type RecordExportScope = 'selected' | 'page' | 'all';

export interface RecordExportScopes {
  /**
   * Rows picked, and **absent** while nothing is picked: a menu offers what
   * the view can actually do (D4), and "export the 0 selected rows" is an
   * item that does nothing.
   */
  selected?: number;
  /** Rows on screen — the page the last result brought back. */
  page: number;
  /**
   * Rows the applied conditions match, or `null` when nobody can say: a
   * cursor source reports no total, and neither does a paged one that
   * withholds it. The export still runs; only the count is unknown.
   */
  all: number | null;
}

export interface RecordExportProgress {
  scope: RecordExportScope;
  /** Rows in hand so far. */
  fetched: number;
  /** How many there are in all, where the source says. */
  total?: number;
}

/** The question `run('all')` stops to ask: this many rows, against this cap. */
export interface RecordExportOverLimit {
  count: number;
  max: number;
}

export interface RecordExportRunOptions {
  /** Answers the over-limit question with "yes, all of it". */
  force?: boolean;
}

export interface RecordExportController {
  scopes: RecordExportScopes;
  /** The scope being exported, or `null` while nothing runs. */
  running: RecordExportScope | null;
  progress: RecordExportProgress | null;
  /** Set when `run('all')` found more rows than the cap; `force` answers it. */
  overLimit: RecordExportOverLimit | null;
  /**
   * The one thing this export has to say: the `error` of a failure, or the
   * `warning` that the ceiling cut the file short where no count could be
   * checked in advance. Nothing while an export is running or has gone well.
   */
  error: Issue | null;
  /** Starts one export; a no-op while another is running. */
  run(scope: RecordExportScope, options?: RecordExportRunOptions): void;
  /** Stops the run, or drops the over-limit question unanswered. */
  cancel(): void;
}

export interface RecordExportOptions {
  /**
   * What becomes of the rows once they are in hand.
   *
   * The hook gathers; `/ui` serialises and hands the file over, because how a
   * value reads and how a browser saves a file are both its answers. A
   * failure here is reported exactly like a failed fetch — from the user's
   * side "the export did not happen" is one outcome, not two.
   */
  deliver(
    rows: readonly RecordData[],
    scope: RecordExportScope,
  ): void | Promise<void>;
}

interface ExportState {
  running: RecordExportScope | null;
  progress: RecordExportProgress | null;
  overLimit: RecordExportOverLimit | null;
  error: Issue | null;
}

const IDLE: ExportState = {
  running: null,
  progress: null,
  overLimit: null,
  error: null,
};

/**
 * Exporting the result: the rows that are picked, the page that is shown, or
 * everything the applied conditions match.
 *
 * The first two are already in hand — they are the result on screen — so they
 * are handed straight over. The third is a query of its own: `exportRows`
 * pages the source behind the view without touching it, which is why a long
 * export leaves the rows, the paging and the selection exactly as they were.
 *
 * `run('all')` asks before it fetches wherever the count is known and above
 * `limits.exportMax`: a number is the one thing that makes "this will take a
 * while" a decision rather than a surprise. Where no total is known there is
 * nothing to ask, so it runs and says afterwards if the ceiling cut it short.
 */
export function useRecordExport(
  runtime: RecordViewRuntime | null,
  table: RecordTableController,
  options: RecordExportOptions,
): RecordExportController {
  const [state, setState] = useState<ExportState>(IDLE);
  // The run in flight, readable between renders: `run` refuses a second
  // export without waiting for a render, and `cancel` reaches this one.
  const live = useRef<AbortController | null>(null);
  // Nothing lands after the component has gone, or after the view it was
  // exporting has been left behind.
  const alive = useRef(true);
  const deliver = options.deliver;
  const delivery = useRef(deliver);
  useEffect(() => {
    delivery.current = deliver;
  }, [deliver]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      live.current?.abort();
      live.current = null;
    };
  }, [runtime]);

  const paging = table.paging;
  const scopes: RecordExportScopes = {
    ...(table.selection.length > 0 ? { selected: table.selection.length } : {}),
    page: table.rows.length,
    all: paging?.mode === 'paged' ? (paging.total ?? null) : null,
  };

  const settle = useCallback((next: ExportState): void => {
    if (alive.current) setState(next);
  }, []);

  /** Serialises and saves, and turns whatever that throws into one Issue. */
  const hand = useCallback(
    async (
      scope: RecordExportScope,
      rows: readonly RecordData[],
      capped: Issue | null,
    ): Promise<void> => {
      try {
        await delivery.current(rows, scope);
        settle({ ...IDLE, error: capped });
      } catch (caught) {
        settle({ ...IDLE, error: toIssue(caught, 'export.failed') });
      }
    },
    [settle],
  );

  const fetchAll = useCallback(
    async (runtime: RecordViewRuntime, total: number | null): Promise<void> => {
      const controller = new AbortController();
      live.current = controller;
      settle({
        running: 'all',
        progress: {
          scope: 'all',
          fetched: 0,
          ...(total === null ? {} : { total }),
        },
        overLimit: null,
        error: null,
      });
      try {
        const result = await runtime.exportRows({
          signal: controller.signal,
          onProgress: (fetched, reported) => {
            if (!alive.current || live.current !== controller) return;
            setState(current => ({
              ...current,
              progress: {
                scope: 'all',
                fetched,
                ...(reported === undefined ? {} : { total: reported }),
              },
            }));
          },
        });
        await hand(
          'all',
          result.rows,
          result.capped
            ? issue(
                'export.capped',
                [],
                { count: result.rows.length },
                'warning',
              )
            : null,
        );
      } catch (caught) {
        // A cancel is the user's own answer, not a finding: the menu closes
        // and nothing is said about it.
        settle(
          isExportCancelled(caught)
            ? IDLE
            : { ...IDLE, error: toIssue(caught, 'export.failed') },
        );
      } finally {
        if (live.current === controller) live.current = null;
      }
    },
    [hand, settle],
  );

  const run = useCallback(
    (
      scope: RecordExportScope,
      { force = false }: RecordExportRunOptions = {},
    ) => {
      if (!runtime || live.current) return;
      if (scope !== 'all') {
        const picked = scope === 'selected' ? table.selectedRows : table.rows;
        // Nothing is in flight for these two — the rows are the result on
        // screen — so `running` is set only for as long as the delivery takes.
        setState({ ...IDLE, running: scope });
        void hand(
          scope,
          picked.map(row => row.data),
          null,
        );
        return;
      }
      const max = runtime.limits.exportMax;
      const total = paging?.mode === 'paged' ? (paging.total ?? null) : null;
      if (!force && total !== null && total > max) {
        setState({ ...IDLE, overLimit: { count: total, max } });
        return;
      }
      void fetchAll(runtime, total);
    },
    [fetchAll, hand, paging, runtime, table.rows, table.selectedRows],
  );

  const cancel = useCallback(() => {
    live.current?.abort();
    live.current = null;
    settle(IDLE);
  }, [settle]);

  return { scopes, ...state, run, cancel };
}
