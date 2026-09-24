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
import {
  exportPlan,
  hasAsked,
  type RecordViewRuntime,
  type ViewEngine,
} from '../../runtime/index.js';
import {
  useFilterEditor,
  useRecordTable,
  useSearchBox,
  useViewRuntime,
} from '../../react/index.js';
import { AppliedBar } from '../AppliedBar.js';
import { Skeleton } from '../components/skeleton.js';
import { ExportButton } from '../ExportDialog.js';
import { useViewMessages } from '../MessagesProvider.js';
import { RecordCards } from '../RecordCards.js';
import { RecordPagination } from '../RecordPagination.js';
import { RecordTable } from '../RecordTable.js';
import { useExportOffer } from '../record/exportOffer.js';
import { QueryStrip } from '../StatusStrip.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { SearchBox } from '../workbench/SearchBox.js';

/**
 * A record view on a business page (D22): its rows and what they were
 * fetched under, and — as the host switched them on — its search, its
 * export and, in the interactive tier, the header sort and the pages.
 *
 * `head` draws the embed's first row; the export is handed to it, since
 * the button belongs there and the rows it takes are known only here.
 */
export function EmbeddedRecord({
  engine,
  runtime,
  interactive,
  withSearch,
  withExport,
  rowActions,
  head,
  notices,
}: {
  engine: ViewEngine;
  runtime: RecordViewRuntime;
  interactive: boolean;
  withSearch: boolean;
  withExport: boolean;
  rowActions?: ((row: RecordRow) => ReactNode) | undefined;
  head(actions: ReactNode): ReactNode;
  /** What the view says about itself, under the head. */
  notices: ReactNode;
}) {
  const state = useViewRuntime(runtime);
  const table = useRecordTable(runtime);
  const filter = useFilterEditor(runtime);
  const searchBox = useSearchBox(runtime);
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const exporter = useExportOffer({
    runtime,
    table,
    filter,
    title: state?.title ?? '',
    messages,
    display,
    now: engine.environment.now,
  });
  // Once rows have landed, and for as long as they are on screen: an export
  // over no result would make an empty file (P-17).
  const exportButton = withExport && table.hasResult && (
    <ExportButton
      {...exporter}
      columns={table.columns}
      max={exportPlan(runtime.limits, runtime.definition.record).max}
    />
  );
  // The search is one of the conditions, so it stands at the applied band's
  // end, as in the workbench; only where the definition declares one.
  const search = withSearch && searchBox && <SearchBox search={searchBox} />;
  const failed = table.status === 'error';
  // A retry is a control, and only the interactive tier has controls on the
  // rows; the read-only one re-runs on its own schedule.
  const retry = interactive ? () => runtime.refresh() : undefined;

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
            selectable={withExport}
          />
        ) : (
          <RecordTable
            table={table}
            rowActions={rowActions}
            selectable={withExport}
            readOnly={!interactive}
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
            className="min-w-0 grow"
          />
          <div className="ml-auto">{search}</div>
        </div>
      ) : (
        <AppliedBar filter={filter} asked={hasAsked(state)} readOnly />
      )}
      {body}
    </>
  );
}
