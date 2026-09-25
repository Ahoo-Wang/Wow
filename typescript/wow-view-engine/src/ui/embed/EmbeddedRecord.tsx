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

import type { ReactNode } from 'react';
import type { RecordRow } from '../../record/index.js';
import { hasAsked, type RecordViewRuntime } from '../../runtime/index.js';
import {
  useFilterEditor,
  useRecordDetail,
  useRecordTable,
  useSearchBox,
  useViewRuntime,
  type RecordDetailSection,
} from '../../react/index.js';
import { AppliedBar } from '../AppliedBar.js';
import { Skeleton } from '../components/skeleton.js';
import { ExportButton } from '../ExportDialog.js';
import { RecordCards } from '../RecordCards.js';
import { RecordPagination } from '../RecordPagination.js';
import { RecordTable } from '../RecordTable.js';
import { useExportOffer } from '../record/exportOffer.js';
import { RecordDetail } from '../record/RecordDetail.js';
import type { RenderFailureHandler } from '../RenderBoundary.js';
import { QueryStrip } from '../StatusStrip.js';
import { SearchBox } from '../workbench/SearchBox.js';
import type { RecordDetailOptions } from '../workbench/RecordParts.js';

/**
 * A record view on a business page (D22): its rows and what they were
 * fetched under, and — as the host switched them on — its search, its
 * export and, in the interactive tier, the header sort, the pages, — with
 * the export — the row checks and — with `detail` — a record's detail,
 * read-only (G20).
 *
 * `head` draws the embed's first row; the export is handed to it, since
 * the button belongs there and the rows it takes are known only here.
 */
export function EmbeddedRecord({
  runtime,
  interactive,
  withSearch,
  withExport,
  rowActions,
  detail: detailOptions,
  onRenderFailure,
  head,
  notices,
}: {
  runtime: RecordViewRuntime;
  interactive: boolean;
  withSearch: boolean;
  withExport: boolean;
  rowActions?: ((row: RecordRow) => ReactNode) | undefined;
  /**
   * The record detail, where the host switched it on and the tier allows
   * it (`null` otherwise): who holds which record is open, and the host's
   * sections in it.
   */
  detail: RecordDetailOptions | null;
  onRenderFailure?: RenderFailureHandler | undefined;
  head(actions: ReactNode): ReactNode;
  /** What the view says about itself, under the head. */
  notices: ReactNode;
}) {
  const state = useViewRuntime(runtime);
  const table = useRecordTable(runtime);
  const filter = useFilterEditor(runtime);
  const searchBox = useSearchBox(runtime);
  // A hook is called either way; without the detail it holds no runtime,
  // so it reads nothing and no row opens anything.
  const detail = useRecordDetail(detailOptions ? runtime : null, {
    open: detailOptions?.open,
    onOpenChange: detailOptions?.onOpenChange,
  });
  const onOpen = detailOptions ? detail.open : undefined;
  const hostSections = detailOptions?.sections;
  const sections = hostSections
    ? (row: RecordRow): readonly RecordDetailSection[] =>
        hostSections({
          row,
          complete: detail.complete,
          runtime,
          refresh: table.refresh,
        })
    : undefined;
  const exporter = useExportOffer({
    runtime,
    table,
    filter,
    title: state?.title ?? '',
  });
  // Once rows have landed, and for as long as they are on screen: an export
  // over no result would make an empty file (P-17).
  // The tier is the ceiling and the switches opt in within it (D36): the
  // export and the search are the reader's controls, so only the
  // interactive tier draws them.
  const exportButton = interactive && withExport && table.hasResult && (
    <ExportButton {...exporter} />
  );
  // The search is one of the conditions, so it stands at the applied band's
  // end, as in the workbench; only where the definition declares one.
  const search = interactive && withSearch && searchBox && (
    <SearchBox search={searchBox} />
  );
  const failed = table.status === 'error';
  // A retry is a control, and only the interactive tier has controls on the
  // rows; the static one re-runs on its own schedule.
  const retry = interactive ? () => runtime.refresh() : undefined;
  // Rows are picked only where the export can take a pick: the interactive
  // tier with the export switched on.
  const selectable = withExport && interactive;

  // A refresh that failed over rows that are still good says so *above*
  // them rather than instead of them: the strip itself promises "the last
  // successful result". Only a failure with nothing behind it replaces the
  // content.
  let body: ReactNode;
  if (failed && !table.hasResult)
    body = <QueryStrip error={table.error} stale={false} onRetry={retry} />;
  else if (table.loading && table.rows.length === 0)
    body = <Skeleton className="h-24 w-full" />;
  else
    body = (
      <>
        <QueryStrip error={failed ? table.error : null} stale onRetry={retry} />
        {table.layout === 'card' ? (
          <RecordCards
            table={table}
            rowActions={rowActions}
            selectable={selectable}
            onOpen={onOpen}
          />
        ) : (
          <RecordTable
            table={table}
            rowActions={rowActions}
            selectable={selectable}
            readOnly={!interactive}
            onOpen={onOpen}
          />
        )}
        {interactive && <RecordPagination table={table} />}
      </>
    );

  return (
    <>
      {head(exportButton)}
      {notices}
      {/*
        What the rows were fetched under, read-only: there is no editor here,
        and the view's own conditions are what its author saved. A ✕ would
        let a reader drop one — on a page that embedded this view to show one
        customer's shipments, that is the page quietly listing everyone's.
      */}
      {search ? (
        <div
          data-slot="applied-row"
          className="flex flex-wrap items-center gap-2"
        >
          <AppliedBar
            filter={filter}
            asked={hasAsked(state)}
            readOnly
            title={state?.title}
            className="min-w-0 grow"
          />
          <div className="ml-auto">{search}</div>
        </div>
      ) : (
        <AppliedBar
          filter={filter}
          asked={hasAsked(state)}
          readOnly
          title={state?.title}
        />
      )}
      {body}
      {/*
        Read-only (D36): the row's commands are not offered in its header,
        so a record opened here is read, never acted on by the engine. The
        host's sections are the host's code, as its row actions are. The
        panel is a sheet like the workbench's: inside a drawer it opens as
        a nested one — Escape closes it alone, and focus goes back to the
        row in this embed. Drawn beside whatever the body is, so a record the
        host opened by key reads while the rows are still on their way.
      */}
      {detailOptions && (
        <RecordDetail
          detail={detail}
          sections={sections}
          onRenderFailure={onRenderFailure}
        />
      )}
    </>
  );
}
