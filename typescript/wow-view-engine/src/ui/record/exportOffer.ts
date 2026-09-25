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

import { useCallback } from 'react';
import type { RecordData, RuntimeLimits } from '../../model/index.js';
import { serializeCsv } from '../../record/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import { exportPlan } from '../../runtime/exportRows.js';
import {
  useRecordExport,
  type FilterEditorController,
  type RecordExportScope,
  type RecordTableController,
} from '../../react/index.js';
import { csvCellText, isoDay } from '../display.js';
import { downloadFile, fileName } from '../download.js';
import type { ExportWindowProps } from '../ExportDialog.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** The media type every export is handed over as: a UTF-8 CSV. */
export const CSV_TYPE = 'text/csv;charset=utf-8';

/**
 * Whether the host's limits leave an export's formulas neutralized
 * (`RuntimeLimits.exportNeutralizeFormulas`): only an explicit `false` turns
 * it off, so limits a host spelled out by hand without the member still
 * write a safe file.
 */
export function neutralizesFormulas(
  limits: Pick<RuntimeLimits, 'exportNeutralizeFormulas'>,
): boolean {
  return limits.exportNeutralizeFormulas !== false;
}

/** One file an export produced, as it was handed over. */
export interface ExportedFile {
  name: string;
  text: string;
  scope: RecordExportScope;
  rows: number;
}

/**
 * What the export window is handed (`ExportWindowProps`) for one open record view
 * — the workbench's toolbar and an embed's head alike (D14, D22).
 *
 * The rows go out as a CSV of the columns the table is drawing, in its
 * order, each value through the same reading the cell above it uses: an
 * export that said `1789723315014` where the screen said a date would be a
 * second, quieter view of the data. The host's scope narrows the export
 * exactly as it narrows the rows, so the window names both kinds of
 * condition.
 *
 * Everything else it reads for itself: the wording and the language and zone
 * a value reads in from the surface it is drawn on, the clock from the
 * runtime, and the columns and the ceiling (`exportPlan`) off the table and
 * the runtime — so a caller hands over the view and nothing it could get
 * wrong. It is therefore called **inside** the surface (`ViewSurface`), where
 * the window it feeds is drawn.
 */
export function useExportOffer({
  runtime,
  table,
  filter,
  title,
  onExported,
}: {
  runtime: RecordViewRuntime;
  table: RecordTableController;
  filter: FilterEditorController;
  /** The view's title, which names the file. */
  title: string;
  onExported?(file: ExportedFile): void;
}): ExportWindowProps {
  const messages = useViewMessages();
  // The language and zone a value reads in, as the cells read it.
  const display = useSurfaceDisplay();
  // The clock a file's day is read from: the engine's.
  const now = runtime.environment.now;
  const columns = table.columns;
  // Named before it exists, because the export window says what the file
  // will be called before there is a file (D14). The clock is read when the
  // window asks — once, as it opens — rather than on every render and again
  // at delivery: a name holds a day in it, and an export that ran across
  // midnight used to be handed over under a name nobody was shown.
  const nameFile = useCallback(
    () => fileName(title, isoDay(now(), display), 'csv'),
    [display, now, title],
  );
  // The host's switch for the file's formulas (`RuntimeLimits`), on unless
  // it is explicitly off.
  const neutralizeFormulas = neutralizesFormulas(runtime.limits);
  const deliver = useCallback(
    (rows: readonly RecordData[], scope: RecordExportScope, name: string) => {
      const text = serializeCsv(
        rows,
        columns,
        (value, column) => csvCellText(value, column, messages, display),
        { neutralizeFormulas },
      );
      downloadFile({ name, text, type: CSV_TYPE });
      onExported?.({ name, text, scope, rows: rows.length });
    },
    [columns, display, messages, neutralizeFormulas, onExported],
  );
  const control = useRecordExport(runtime, table, { deliver });
  return {
    control,
    conditions: [...filter.applied, ...filter.scoped, ...filter.implied],
    nameFile,
    columns,
    // The ceiling the export will really stop at: the limit, and the
    // source's paging window below it where one is declared.
    max: exportPlan(runtime.limits, runtime.definition.record).max,
  };
}
