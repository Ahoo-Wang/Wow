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
import type { DataViewConfig } from '../../model/index.js';
import {
  isRecordRuntime,
  type RecordViewRuntime,
  type ViewRuntime,
} from '../../runtime/index.js';
import { useFilterEditor, useRecordTable } from '../../react/index.js';
import { useAnalysisExportOffer } from '../analysis/exportOffer.js';
import { ExportDialog, type ExportWindowProps } from '../ExportDialog.js';
import { useExportOffer } from '../record/exportOffer.js';

/** What the window over a panel is told beyond the offer itself. */
interface PanelExportProps {
  /** What the board calls the panel; the file is named after it. */
  name: string;
  open: boolean;
  onOpenChange(open: boolean): void;
  returnTo: RefObject<HTMLElement | null>;
}

/**
 * 「导出数据…」 from a panel's 「⋯」 (D22 运维): the workbench's own export
 * window (D14) over the panel's child view, delivered as the workbench
 * delivers it — a record panel's rows by `useExportOffer`, the columns the
 * panel draws, each value as its cell reads; an analysis panel's groups by
 * `useAnalysisExportOffer`, the table's reading whether the panel draws a
 * table or a chart (D25 Q28). The conditions named are those the rows came
 * back under, the board's filters as they reach this panel among them
 * (the child's scope), so the window names them and the file holds what the
 * panel shows.
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
  ...props
}: PanelExportProps & { runtime: ViewRuntime<DataViewConfig> }) {
  return isRecordRuntime(runtime) ? (
    <RecordPanelExport runtime={runtime} {...props} />
  ) : (
    <AnalysisPanelExport runtime={runtime} {...props} />
  );
}

function RecordPanelExport({
  runtime,
  name,
  ...props
}: PanelExportProps & { runtime: RecordViewRuntime }) {
  const table = useRecordTable(runtime);
  const filter = useFilterEditor(runtime);
  const offer = useExportOffer({ runtime, table, filter, title: name });
  return <PanelWindow offer={offer} {...props} />;
}

function AnalysisPanelExport({
  runtime,
  name,
  ...props
}: PanelExportProps & { runtime: ViewRuntime }) {
  const offer = useAnalysisExportOffer({ runtime, title: name });
  // The groups went while the window was closed — a refresh that matched
  // nothing: there is no file to offer, and the menu no longer offers one.
  return offer && <PanelWindow offer={offer} {...props} />;
}

function PanelWindow({
  offer,
  open,
  onOpenChange,
  returnTo,
}: Omit<PanelExportProps, 'name'> & { offer: ExportWindowProps }) {
  // One stable function (the compiler memoises it on `returnTo`): the
  // window's focus manager re-arms whenever it is handed a new one, and
  // hands the keyboard back on the way.
  const finalFocus = () => returnTo.current ?? true;
  return (
    <ExportDialog
      {...offer}
      open={open}
      onOpenChange={onOpenChange}
      finalFocus={finalFocus}
    />
  );
}
