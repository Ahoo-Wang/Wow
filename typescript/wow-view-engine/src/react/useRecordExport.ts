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
import type { Issue, RecordData } from '../model/index.js';
import { isExportCancelled, type RecordViewRuntime } from '../runtime/index.js';
import { toIssue } from './issues.js';
import type { RecordTableController } from './useRecordTable.js';

/**
 * The two readings of "export": what is picked, and what the conditions
 * match.
 *
 * "This page" used to be a third, and it was an artefact of paging rather
 * than an intent (D14): on a result that fits one page it is "all" said
 * twice, and on one that does not it is a slice cut by the sort and the page
 * size that nobody asked for. A sample is the header's select-all followed
 * by "selected".
 */
export type RecordExportScope = 'selected' | 'all';

export interface RecordExportScopes {
  /**
   * Rows picked, and **absent** while nothing is picked: the dialog offers
   * what the view can actually do (D4), and "export the 0 selected rows" is
   * a choice that does nothing.
   */
  selected?: number;
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

/** What one finished export produced, until the next run or `reset()`. */
export interface RecordExportOutcome {
  scope: RecordExportScope;
  /** Rows the file holds. */
  rows: number;
  /** The ceiling stopped the fetch short of everything that matched. */
  capped: boolean;
  /** How many rows matched in all, where the source reports a total. */
  total?: number;
}

export interface RecordExportController {
  scopes: RecordExportScopes;
  /** The scope being exported, or `null` while nothing runs. */
  running: RecordExportScope | null;
  progress: RecordExportProgress | null;
  /** The export that finished, so the window can report what it produced. */
  outcome: RecordExportOutcome | null;
  /**
   * The failure of the last export, as one Issue. Nothing while an export is
   * running or has gone well — a file cut short by the ceiling is not a
   * failure, and says so through `outcome.capped`.
   */
  error: Issue | null;
  /**
   * Starts one export; a no-op while another is running.
   *
   * `fileName` is what the window that starts the run has already promised
   * the file will be called. It is carried to `deliver` untouched rather
   * than worked out again when the rows are in hand: the name holds a day,
   * and the export may outlive one (D14).
   */
  run(scope: RecordExportScope, fileName: string): void;
  /** Stops the run in flight. */
  cancel(): void;
  /** Forgets the last outcome or failure; it does not stop a run. */
  reset(): void;
}

export interface RecordExportOptions {
  /**
   * What becomes of the rows once they are in hand.
   *
   * The hook gathers; `/ui` serialises and hands the file over, because how a
   * value reads and how a browser saves a file are both its answers. A
   * failure here is reported exactly like a failed fetch — from the user's
   * side "the export did not happen" is one outcome, not two.
   *
   * `fileName` is the name the run was started under, so the file that is
   * handed over is the file that was promised.
   */
  deliver(
    rows: readonly RecordData[],
    scope: RecordExportScope,
    fileName: string,
  ): void | Promise<void>;
}

interface ExportState {
  running: RecordExportScope | null;
  progress: RecordExportProgress | null;
  outcome: RecordExportOutcome | null;
  error: Issue | null;
}

const IDLE: ExportState = {
  running: null,
  progress: null,
  outcome: null,
  error: null,
};

/**
 * Exporting the result: the rows that are picked, or everything the applied
 * conditions match.
 *
 * The picked rows are already in hand — they are the result on screen — so
 * they are handed straight over. "All" is a query of its own: `exportRows`
 * pages the source behind the view without touching it, which is why a long
 * export leaves the rows, the paging and the selection exactly as they were.
 *
 * It asks nothing. The one question there was — "this is more than the cap,
 * still want it?" — is now part of the window that starts the export (D14):
 * the count and the ceiling are on screen before the button is pressed, so
 * pressing it **is** the consent, and a hook that stopped to ask again would
 * be asking twice. Where no total is known there was never anything to ask,
 * and `outcome.capped` says afterwards that the ceiling cut the file short.
 */
export function useRecordExport(
  runtime: RecordViewRuntime | null,
  table: RecordTableController,
  options: RecordExportOptions,
): RecordExportController {
  const [state, setState] = useState<ExportState>(IDLE);
  // Whether an export is in flight, readable between renders: `run` refuses
  // a second one without waiting for a render, which a double click on the
  // window's button would otherwise get — the picked scope fetches nothing,
  // so `live` below is empty for it and cannot be the gate.
  const busy = useRef(false);
  // The fetch in flight, so `cancel` reaches this one and a progress report
  // from a superseded run is ignored.
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
      busy.current = false;
      live.current?.abort();
      live.current = null;
    };
  }, [runtime]);

  const paging = table.paging;
  const scopes: RecordExportScopes = {
    ...(table.selection.length > 0 ? { selected: table.selection.length } : {}),
    all: paging?.mode === 'paged' ? (paging.total ?? null) : null,
  };

  const settle = useCallback((next: ExportState): void => {
    busy.current = next.running !== null;
    if (alive.current) setState(next);
  }, []);

  /** Serialises and saves, and turns whatever that throws into one Issue. */
  const hand = useCallback(
    async (
      rows: readonly RecordData[],
      outcome: RecordExportOutcome,
      fileName: string,
    ): Promise<void> => {
      try {
        await delivery.current(rows, outcome.scope, fileName);
        settle({ ...IDLE, outcome });
      } catch (caught) {
        settle({ ...IDLE, error: toIssue(caught, 'export.failed') });
      }
    },
    [settle],
  );

  const fetchAll = useCallback(
    async (
      runtime: RecordViewRuntime,
      total: number | null,
      fileName: string,
    ): Promise<void> => {
      const controller = new AbortController();
      live.current = controller;
      settle({
        ...IDLE,
        running: 'all',
        progress: {
          scope: 'all',
          fetched: 0,
          ...(total === null ? {} : { total }),
        },
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
          result.rows,
          {
            scope: 'all',
            rows: result.rows.length,
            capped: result.capped,
            ...(total === null ? {} : { total }),
          },
          fileName,
        );
      } catch (caught) {
        // A cancel is the user's own answer, not a finding: the window
        // closes and nothing is said about it.
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
    (scope: RecordExportScope, fileName: string) => {
      if (!runtime || busy.current) return;
      busy.current = true;
      if (scope === 'selected') {
        const picked = table.selectedRows;
        // Nothing is fetched for this one — the rows are the result on
        // screen — so `running` is set only for as long as delivery takes.
        settle({ ...IDLE, running: scope });
        void hand(
          picked.map(row => row.data),
          { scope, rows: picked.length, capped: false },
          fileName,
        );
        return;
      }
      void fetchAll(
        runtime,
        paging?.mode === 'paged' ? (paging.total ?? null) : null,
        fileName,
      );
    },
    [fetchAll, hand, paging, runtime, settle, table.selectedRows],
  );

  const cancel = useCallback(() => {
    live.current?.abort();
    live.current = null;
    settle(IDLE);
  }, [settle]);

  // Closing the window forgets what the last run produced, so opening it
  // again asks rather than reporting an export the user has already read.
  // Stopping one is `cancel`; this one leaves a run in flight alone.
  const reset = useCallback(() => {
    if (!busy.current) settle(IDLE);
  }, [settle]);

  return { scopes, ...state, run, cancel, reset };
}
