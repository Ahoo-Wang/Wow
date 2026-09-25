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

import type * as React from 'react';
import { XIcon } from 'lucide-react';
import type {
  RecordBulkActionContext,
  RecordTableController,
} from '../../react/index.js';
import type { RecordViewRuntime } from '../../runtime/index.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import { IconTooltip } from '../IconButton.js';
import { SPACE } from '../layout.js';
import { Toolbar, ToolbarItem } from '../toolbar.js';
import { useViewMessages } from '../MessagesProvider.js';

/** What the selection shows: its count, the way to drop it, the host's bulk slot. */
interface SelectionProps {
  table: RecordTableController;
  runtime: RecordViewRuntime;
  bulkActions?(context: RecordBulkActionContext): React.ReactNode;
}

/**
 * The selection's part of a toolbar, inside a `Toolbar` of the caller's:
 * the result toolbar's left end, and a dashboard's record panel's own bar
 * (`SelectionBar`, D39).
 */
export function SelectionGroup({
  table,
  runtime,
  bulkActions,
}: SelectionProps) {
  const messages = useViewMessages();
  return (
    <div
      data-slot="toolbar-selection"
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      {/* The count and the way to drop it are one thing, so they sit
          4px apart inside the 8px the groups keep between them: a ✕ is
          read as belonging to whatever it is against, and what this one
          clears is the number beside it. The badge keeps `role=status`
          to itself — a control inside a live region would be announced
          again on every change of the count. */}
      <div
        data-slot="toolbar-selection-count"
        className="flex items-center gap-1"
      >
        <Badge variant="secondary" role="status">
          {messages.label('label.toolbar.selected', {
            count: table.selection.length,
          })}
        </Badge>
        {/* **A glyph rather than a word** (P-05). As a `ghost` button
            with a label in it, this was 74px of unframed 13px text
            beside the host's framed 74px bulk action — the same size,
            the same place, and no border: it read as the badge's
            caption. `outline` would have made it read as pressable and
            also as the host's peer, first in the row and heaviest on
            the left, when clearing a selection is the way back from the
            actions rather than one of them. A ✕ against the count is
            the shape everything else uses for "drop this", stays
            `ghost` like the rest of this bar, and gives the left 46px
            back — on a phone the bar is three lines of controls. The
            name is unchanged and said the way D12 says every icon
            button's: `aria-label` plus the tooltip, over one string. */}
        <IconTooltip
          label={messages.label('label.toolbar.clear-selection')}
          render={
            <ToolbarItem
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={table.clearSelection}
                />
              }
            />
          }
        >
          <XIcon />
        </IconTooltip>
      </div>
      {/* The host's own controls stay as they came: a bulk slot holds
          arbitrary nodes, and an item can only be made of an element
          this file renders. They are ordinary tab stops between the two
          ends of the bar, which is the honest reading — the toolbar
          does not own them. */}
      {bulkActions?.({
        rows: table.selectedRows,
        keys: table.selection,
        runtime,
        clearSelection: table.clearSelection,
        select: table.select,
        refresh: table.refresh,
      })}
    </div>
  );
}

/**
 * The selection on a surface with no result toolbar — a dashboard's record
 * panel whose host brought a bulk action (D39): the count, the way to drop
 * it and the host's bulk slot, one toolbar, only while rows are picked.
 */
export function SelectionBar(props: SelectionProps) {
  const messages = useViewMessages();
  if (props.table.selection.length === 0) return null;
  return (
    <Toolbar
      data-slot="panel-selection"
      aria-label={messages.label('label.toolbar.title')}
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      <SelectionGroup {...props} />
    </Toolbar>
  );
}
