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

import { useId } from 'react';
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react';
import type { RecordTableController } from '../react/index.js';
import { Button } from './components/button.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { SelectContent } from './popups.js';
import { useViewMessages } from './MessagesProvider.js';

export interface RecordPaginationProps {
  table: RecordTableController;
}

/**
 * How many rows there are and how to reach the next of them.
 *
 * It sits under the result rather than in the toolbar because it is about
 * the rows, not about the view: paging leaves no trace in a saved config,
 * and the page a user was on is not part of how they look at records.
 *
 * One row, read left to right: how many records there are in all, then — at
 * the far end, where the hand already is — the page size, which page this is,
 * and the two steps out of it. The count leads because it is the answer to
 * the question the conditions above were asked; the controls follow it
 * because they are only how to see the rest of it.
 */
export function RecordPagination({ table }: RecordPaginationProps) {
  const messages = useViewMessages();
  const sizeLabelId = useId();
  const paging = table.paging;
  const paged = paging?.mode === 'paged';
  // The controller decides what may be offered: the ladder cut to the
  // runtime's `maxPageSize`, with the size in force folded in.
  const sizes = table.pageSizes;

  // Past the first page there is always a way back, and it is the only way
  // back: a later page that came up empty — rows deleted since, or a source
  // that reports no total and answered one page too far — would otherwise
  // take Previous off the screen and leave the user on an empty page with
  // nothing to press.
  const stranded = paged && paging.index > 1;

  // The first load, with no result behind it: every number this bar could
  // show would be made up. "0 on this page" counts rows that have not
  // arrived, and beside it a live `›` offers a next page nobody — not even
  // the runtime — knows exists. A bar that says nothing is the only honest
  // one until a result has been counted; the skeleton above already says a
  // query is running, and that is the whole of what is known.
  //
  // Narrow on purpose, and the narrowness is the point: a *refresh* keeps
  // the rows it has not replaced yet, so its counts are last result's and
  // true of what is on screen. Those stay.
  if (!table.hasResult && table.status === 'loading') return null;

  // Nothing to page through, and nothing on the way: an empty result says so
  // on its own. While a query runs the last rows are still on screen, so the
  // counts below stay with them rather than blanking and jumping back.
  if (table.rows.length === 0 && table.status !== 'loading' && !stranded)
    return null;

  const total = paged ? paging.total : undefined;

  // A total is what the reader asked about — how much matches, not how much
  // arrived — so it is the sentence whenever the source gave one. A cursor
  // source never gives one, and rather than infer a total from a page that
  // happens to be full, the bar says the one number it actually holds.
  const count =
    total === undefined
      ? messages.label('label.pagination.on-page', {
          count: table.rows.length,
        })
      : messages.label('label.pagination.total', { total });

  // The unit belongs to the number, not to the words in front of it: Chinese
  // counts records with a measure word (`20 条`), so the option carries it
  // and `每页` stays a preposition rather than becoming `每页 20`.
  const sizeLabel = (size: number) =>
    messages.label('label.pagination.page-size-option', { size });

  return (
    <div
      data-slot="record-pagination"
      // One line while there is room for one, two when there is not. A row
      // that could not wrap put Next's right edge 24px past the result card
      // it sits in at a phone's width, and `justify-between` squeezed the
      // count instead: "4 records in all" broke over three lines and the
      // Chinese "共 4 条记录" broke mid-word. The sentence keeps itself
      // whole, the controls take the line below it, and `ml-auto` keeps
      // them at the end of whichever line they land on.
      className="text-muted-foreground flex flex-wrap items-center gap-x-4 gap-y-2 text-xs"
    >
      <span className="whitespace-nowrap">{count}</span>

      <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
        <span id={sizeLabelId}>
          {messages.label('label.pagination.page-size')}
        </span>
        <Select
          items={sizes.map(size => ({ value: size, label: sizeLabel(size) }))}
          value={table.pageSize}
          onValueChange={value => {
            // Picked out of the offered sizes rather than cast: the list is
            // what the control was built from.
            const next = sizes.find(size => size === value);
            if (next !== undefined) table.setPageSize(next);
          }}
        >
          {/* Named by the words beside it rather than by an `aria-label` of
              its own, so the control announces what the row already reads —
              one label, not two that have to be kept in step. */}
          <SelectTrigger size="sm" aria-labelledby={sizeLabelId}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {sizes.map(size => (
                <SelectItem key={size} value={size}>
                  {sizeLabel(size)}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>

        {/* A cursor source has no page numbers and no way back, so it shows
            neither rather than showing them dead. */}
        {paged && (
          <span>
            {paging.total === undefined || table.pageSize <= 0
              ? messages.label('label.toolbar.page', { index: paging.index })
              : messages.label('label.toolbar.page-of', {
                  index: paging.index,
                  pages: Math.max(1, Math.ceil(paging.total / table.pageSize)),
                })}
          </span>
        )}
        <div className="flex items-center gap-1">
          {paged && (
            <Button
              variant="outline"
              size="icon-sm"
              aria-label={messages.label('label.toolbar.previous')}
              disabled={paging.index <= 1}
              onClick={table.previous}
            >
              <ChevronLeftIcon />
            </Button>
          )}
          <Button
            variant="outline"
            size="icon-sm"
            aria-label={messages.label('label.toolbar.next')}
            disabled={!table.hasNext}
            onClick={table.next}
          >
            <ChevronRightIcon />
          </Button>
        </div>
      </div>
    </div>
  );
}
