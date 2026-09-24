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

import type { RefObject } from 'react';
import { exportPlan, type RecordViewRuntime } from '../../runtime/index.js';
import { useFilterEditor, useRecordTable } from '../../react/index.js';
import { ExportDialog } from '../ExportDialog.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useExportOffer } from '../record/exportOffer.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/**
 * 「导出数据…」 from a record panel's 「⋯」 (D22 运维): the workbench's own
 * export window (D14) over the panel's child view, delivered by the same
 * `useExportOffer` — the columns the panel draws, each value as its cell
 * reads, under the conditions the rows came back under. Those include the
 * board's filters as they reach this panel, mapped onto the view's fields
 * (the child's scope), so the window names them and the file holds what
 * the panel shows.
 *
 * The file is named after the panel, as the board names it: that is what
 * its reader was looking at, and a panel title of the author's own is the
 * board's word for it. There is no 「选中」: a panel's rows carry no
 * checkboxes, so the window offers every row the conditions match.
 *
 * The item that opens it goes with the menu, so the keyboard goes back to
 * the panel's 「⋯」 (`returnTo`) as the window closes.
 */
export function PanelExport({
  runtime,
  name,
  open,
  onOpenChange,
  returnTo,
}: {
  runtime: RecordViewRuntime;
  /** What the board calls the panel; the file is named after it. */
  name: string;
  open: boolean;
  onOpenChange(open: boolean): void;
  returnTo: RefObject<HTMLElement | null>;
}) {
  const table = useRecordTable(runtime);
  const filter = useFilterEditor(runtime);
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const exporter = useExportOffer({
    runtime,
    table,
    filter,
    title: name,
    messages,
    display,
    now: runtime.environment.now,
  });
  // One stable function (the compiler memoises it on `returnTo`): the
  // window's focus manager re-arms whenever it is handed a new one, and
  // hands the keyboard back on the way.
  const finalFocus = () => returnTo.current ?? true;
  return (
    <ExportDialog
      {...exporter}
      columns={table.columns}
      max={exportPlan(runtime.limits, runtime.definition.record).max}
      open={open}
      onOpenChange={onOpenChange}
      finalFocus={finalFocus}
    />
  );
}
