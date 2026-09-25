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

import { useCallback, useMemo, useState } from 'react';
import type { AnalysisColumnView, AnalysisView } from '../../analysis/index.js';
import type { Issue, RecordData } from '../../model/index.js';
import {
  writeCsv,
  type CsvCell,
  type CsvOptions,
} from '../../record/export.js';
import { toIssue } from '../../runtime/issues.js';
import { reportViewFailure } from '../../runtime/failures.js';
import type { ViewRuntime } from '../../runtime/index.js';
import {
  useFilterEditor,
  useViewRuntime,
  type RecordExportController,
  type RecordExportOutcome,
} from '../../react/index.js';
import { columnTitle, isoDay, type DisplayContext } from '../display.js';
import { downloadFile, fileName } from '../download.js';
import type { ExportWindowProps } from '../ExportDialog.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { CSV_TYPE, neutralizesFormulas } from '../record/exportOffer.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { analysisCellText } from './tableColumns.js';

/** An analysis result as the file that takes it away holds it. */
export interface AnalysisFile {
  /** The header row: what each column is headed in the table. */
  columns: readonly { label: string }[];
  /**
   * Every row the file holds, as read — before the writer escapes it or
   * neutralizes a formula in it; the totals row last, where shown.
   */
  rows: readonly (readonly string[])[];
  /** The CSV itself (`writeCsv`), byte order mark included. */
  text: string;
}

/**
 * The result as its table reads it, as a file (D25 Q28): the group columns
 * first and the metrics after them, each in the order the table draws it
 * and headed as the table heads it (`columnTitle`); every group on screen —
 * the first N, never the probe row that said there are more; and the totals
 * row last where the table shows one, 「合计」 in its first column.
 *
 * It is read off the view (`AnalysisView`), never off the chart's
 * projection: the 0 a chart fills into a missing combination and the
 * 「其他」 a pie folds its tail into are drawn, not measured (D23 Q14), so
 * the file is the same whether the table or the chart is showing.
 *
 * Every cell is read as the table's cell reads it (`analysisCellText`) —
 * a count as 1,204 and an amount in its currency, as on screen: every
 * metric carries the format the kernel settled for it (`metricFormat`), so
 * the file writes the figure the reader saw rather than a second, rawer one.
 */
export function analysisFile(
  view: AnalysisView,
  messages: MessageFormatters,
  display: DisplayContext,
  options: CsvOptions = {},
): AnalysisFile {
  const columns: AnalysisColumnView[] = [
    ...view.columns.filter(column => column.role === 'group'),
    ...view.columns.filter(column => column.role === 'metric'),
  ];
  // Each cell keeps the value it was read from beside its text, because the
  // writer's formula rule asks whether it was a number (`CsvOptions`): a
  // negative sum reads 「-$12.00」 and is still no formula.
  const read = (row: RecordData): CsvCell[] =>
    columns.map(column => {
      const value = row[column.alias];
      return {
        value,
        text: analysisCellText(value, column, messages, display),
      };
    });
  const cells = view.rows.map(read);
  if (view.totals) {
    const totals = read(view.totals);
    // The word the table's totals row starts with, in the group column it
    // is said in — there is always one: no dimension, no totals row.
    if (columns[0]?.role === 'group') {
      const total = messages.label('label.summary.total');
      totals[0] = { value: total, text: total };
    }
    cells.push(totals);
  }
  const header = columns.map(column => ({
    label: columnTitle(column, messages),
  }));
  // Handed over already read, so the writer escapes and never reads: a row
  // is its cells by position, whatever the aliases are called.
  const text = writeCsv(
    header.map(column => column.label),
    cells,
    options,
  );
  const rows = cells.map(row => row.map(cell => cell.text));
  return { columns: header, rows, text };
}

/**
 * What the file holds, in groups rather than records: 「12 组」, 「前 50 组
 * （后面还有组，不在文件里）」 where the result was cut short, 「1 行：范围内的
 * 全部记录」 with no dimension — each 「，另加一行合计」 where the totals row
 * is shown.
 */
function holding(
  view: AnalysisView,
  messages: MessageFormatters,
): NonNullable<ExportWindowProps['holds']> {
  const count = view.rows.length;
  const grouped = view.columns.some(column => column.role === 'group');
  const groups = !grouped
    ? messages.label('label.export.whole')
    : view.truncated
      ? messages.label('label.export.groups-first', { count })
      : count === 1
        ? messages.label('label.export.groups-one')
        : messages.label('label.export.groups', { count });
  const rows = view.totals
    ? messages.label('label.export.and-totals', { rows: groups })
    : groups;
  return {
    rows,
    done: messages.label('label.export.done-analysis', { rows }),
  };
}

/** One press of 「导出」 answered: what the file held, or why it did not go. */
interface Answer {
  outcome: RecordExportOutcome | null;
  error: Issue | null;
}

const UNANSWERED: Answer = { outcome: null, error: null };

/**
 * What the export window (`ExportDialog`, D14) is handed for one analysis —
 * the workbench's result toolbar and an analysis panel's 「导出数据…」 alike
 * (D25 Q28) — or `null` while there is no group to take away: before the
 * first answer, after one that failed, and over a range that matched
 * nothing, as the record view offers no export over no result (P-17).
 *
 * The rows are in hand, so there is no scope to pick and nothing to wait
 * for: the window says what the file holds (`holds`), and 「导出」 goes
 * straight to the outcome. The conditions are those the rows came back
 * under — the view's own, the host's scope and, in a panel, the board's
 * filters as they reach it — named as the record export names them.
 *
 * Called inside the surface (`ViewSurface`), as `useExportOffer` is: the
 * wording, the language and the zone are the surface's.
 */
export function useAnalysisExportOffer({
  runtime,
  title,
}: {
  runtime: ViewRuntime | null;
  /** The view's or the panel's title, which names the file. */
  title: string;
}): ExportWindowProps | null {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const state = useViewRuntime(runtime);
  const filter = useFilterEditor(runtime);
  const data = state?.result?.data;
  const view = data?.kind === 'analysis' ? data.view : null;
  // The host's switch for the file's formulas, as the record export reads it.
  const neutralizeFormulas = runtime
    ? neutralizesFormulas(runtime.limits)
    : true;
  const file = useMemo(
    () =>
      view
        ? analysisFile(view, messages, display, { neutralizeFormulas })
        : null,
    [display, messages, neutralizeFormulas, view],
  );
  const [answer, setAnswer] = useState<Answer>(UNANSWERED);
  const now = runtime?.environment.now;
  // Named before it exists and asked once, as the window opens (D14).
  const nameFile = useCallback(
    () => fileName(title, isoDay(now ? now() : new Date(), display), 'csv'),
    [display, now, title],
  );
  if (!view || !file || view.rows.length === 0) return null;
  // The groups, the totals row not counted: 「已导出 12 组」 is about groups.
  const groups = view.rows.length;
  const control: RecordExportController = {
    scopes: { all: groups },
    running: null,
    progress: null,
    ...answer,
    run: (_scope, name) => {
      try {
        downloadFile({ name, content: file.text, type: CSV_TYPE });
        setAnswer({
          outcome: { scope: 'all', rows: groups, capped: false },
          error: null,
        });
      } catch (caught) {
        reportViewFailure(runtime, 'export', 'deliver', caught);
        setAnswer({ outcome: null, error: toIssue(caught, 'export.failed') });
      }
    },
    cancel: () => undefined,
    reset: () => setAnswer(UNANSWERED),
  };
  return {
    control,
    conditions: [...filter.applied, ...filter.scoped, ...filter.implied],
    nameFile,
    columns: file.columns,
    // Nothing to cut: every group on screen is in hand.
    max: groups,
    holds: holding(view, messages),
  };
}
