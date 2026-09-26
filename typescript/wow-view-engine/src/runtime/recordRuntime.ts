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

import { QueryErrorCodes } from '@ahoo-wang/wow-client';
import type {
  DataViewConfig,
  RecordData,
  RecordKey,
  RecordPageTarget,
  RecordViewConfig,
} from '../model/index.js';
import { pageAfterShrink } from '../record/index.js';
import { firstPageOf } from './execute.js';
import {
  fetchExportRows,
  isExportCancelled,
  type ExportRowsOptions,
  type ExportedRows,
} from './exportRows.js';
import { fetchRecord } from './fetchRecord.js';
import { isCalledOff } from './failures.js';
import { withScopeFilter } from './scope.js';
import type { SourceFailure } from './sourceReason.js';
import type { ProjectedView } from './source.js';
import { DataViewRuntime } from './viewRuntime.js';
import type {
  RecordViewRuntime,
  ViewRuntime,
  ViewRuntimeOptions,
  ViewRuntimeState,
} from './viewRuntimeTypes.js';

/**
 * The runtime of a Record view: the shared data view, plus what only rows
 * have — the page a query asks for, the rows picked, every row for an
 * export, one record read whole.
 *
 * Each is here and nowhere else, so an Analysis runtime carries no page it
 * never turns and no `exportRows` it can only refuse.
 */
export class RecordDataViewRuntime
  extends DataViewRuntime<RecordViewConfig>
  implements RecordViewRuntime
{
  /** The page the next query asks for. */
  private target: RecordPageTarget | undefined;
  /**
   * Whether the page on screen is one a refused cursor already sent back
   * to the start; cleared when a page lands, so the fall-back is once per
   * refusal and never a loop.
   */
  private restarted = false;
  /** Tells the host of an export whose rows could not be fetched (D40). */
  private readonly exportFailed = this.reporter('export');

  constructor(options: ViewRuntimeOptions<RecordViewConfig>) {
    super(options);
    this.target = firstPageOf(options.definition);
  }

  page(target: RecordPageTarget): void {
    if (this.disposed || !this.appliedAdmitted) return;
    this.target = target;
    this.store.setState({ selection: [] });
    this.execute({ keepSelection: false });
  }

  select(keys: RecordKey[]): void {
    if (this.disposed) return;
    const available = this.resultKeys();
    const selection = available
      ? keys.filter(key => available.has(key))
      : [...keys];
    this.store.setState({ selection });
  }

  /**
   * The applied config's rows, all of them, for an export.
   *
   * It reads `applied` merged with the scope — the very config the result on
   * screen came from — and goes straight to the source: the request runner is
   * the view's own lane and an export must neither queue behind the view nor
   * push it aside.
   */
  exportRows(options: ExportRowsOptions = {}): Promise<ExportedRows> {
    if (this.disposed)
      return Promise.reject(new Error(`View ${this.id} is closed`));
    return fetchExportRows(
      this.context,
      withScopeFilter(this.state.applied, this.scopeFilter),
      options,
    ).catch((error: unknown) => {
      // A cancel is the user's answer, not a failure (`isExportCancelled`).
      if (!isExportCancelled(error) && !isCalledOff(error, options.signal))
        this.exportFailed('fetch', error);
      throw error;
    });
  }

  fetchRecord(
    key: RecordKey,
    signal?: AbortSignal,
  ): Promise<RecordData | null> {
    if (this.disposed)
      return Promise.reject(new Error(`View ${this.id} is closed`));
    return fetchRecord(
      this.context,
      this.state.applied,
      this.scopeFilter,
      key,
      signal,
    ).catch((error: unknown) => {
      if (!isCalledOff(error, signal))
        void this.context.queryFailed('record', error);
      throw error;
    });
  }

  /** A new question starts on the first page, with nothing picked. */
  protected override startOver(): Partial<ViewRuntimeState<RecordViewConfig>> {
    this.target = firstPageOf(this.context.definition);
    return { selection: [] };
  }

  protected override pageNow(): RecordPageTarget | undefined {
    return this.target;
  }

  /**
   * A cursor the source no longer reads — a token from before the service
   * changed how it writes one (#3502), or one a replica does not know —
   * sends the view back to the first page once, rather than showing a
   * failure the reader can do nothing about but start over by hand.
   */
  protected override recovers(failure: SourceFailure): boolean {
    const target = this.target;
    if (this.restarted || !target || !('cursor' in target)) return false;
    if (target.cursor === null || !isInvalidCursor(failure)) return false;
    this.restarted = true;
    this.target = firstPageOf(this.context.definition);
    return true;
  }

  /**
   * A page the result shrank out from under asks for the last page there is
   * rather than landing empty with rows before it; a refresh keeps whatever
   * picked rows survive it.
   */
  protected override settle(
    data: ProjectedView,
    keepSelection: boolean,
  ): Partial<ViewRuntimeState<RecordViewConfig>> | null {
    this.restarted = false;
    const back = this.shrunkTo(data);
    if (back !== null) {
      this.target = { index: back };
      return null;
    }
    if (!keepSelection) return {};
    const selection = this.retainSelection(data);
    return selection === this.state.selection ? {} : { selection };
  }

  /**
   * Picked rows are rows someone is about to act on, and a refresh can move
   * them to another page or out of the result: the clock waits.
   */
  protected override holds(): boolean {
    return this.state.selection.length > 0;
  }

  private shrunkTo(data: ProjectedView): number | null {
    const target = this.target;
    if (data.kind !== 'record' || !target || !('index' in target)) return null;
    return pageAfterShrink(
      data.view.paging,
      target.index,
      data.view.rows.length,
    );
  }

  private retainSelection(data: ProjectedView): RecordKey[] {
    if (this.state.selection.length === 0 || data.kind !== 'record')
      return this.state.selection;
    const keys = new Set(data.view.rows.map(row => row.key));
    const retained = this.state.selection.filter(key => keys.has(key));
    return retained.length === this.state.selection.length
      ? this.state.selection
      : retained;
  }

  private resultKeys(): Set<RecordKey> | null {
    const data = this.state.result?.data;
    if (!data || data.kind !== 'record') return null;
    return new Set(data.view.rows.map(row => row.key));
  }
}

/**
 * A Wow service's answer to a cursor it cannot read: `INVALID_CURSOR` from
 * Wow 9.2 on, and before that these words alone (`CursorPositions`).
 */
const INVALID_CURSOR = 'Invalid cursor.';

function isInvalidCursor(failure: SourceFailure): boolean {
  if (failure.violation)
    return failure.violation.code === QueryErrorCodes.INVALID_CURSOR;
  return (
    (failure.status === undefined || failure.status === 400) &&
    failure.reason.includes(INVALID_CURSOR)
  );
}

/** The runtime a data view of this config's kind runs on. */
export function dataViewRuntime(
  options: ViewRuntimeOptions<DataViewConfig>,
): DataViewRuntime {
  return options.config.kind === 'record'
    ? new RecordDataViewRuntime(options as ViewRuntimeOptions<RecordViewConfig>)
    : new DataViewRuntime(options);
}

/**
 * Whether a data view's runtime is a Record view's, with its rows' commands:
 * a dashboard panel's runtime (`DashboardPanelState.runtime`) is either kind.
 */
export function isRecordRuntime(
  runtime: ViewRuntime<DataViewConfig>,
): runtime is RecordViewRuntime {
  return runtime instanceof RecordDataViewRuntime;
}
