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

import { useEffect, useId, useRef } from 'react';
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react';
import type { RecordTableController } from '../react/index.js';
import { IconButton } from './IconButton.js';
import { Input } from './components/input.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { SelectContent } from './popups.js';
import {
  Pagination,
  PaginationContent,
  PaginationItem,
} from './components/pagination.js';
import { useViewMessages } from './MessagesProvider.js';
import { TEXT_UI } from './layout.js';
import { cn } from 'cn';

export interface RecordPaginationProps {
  table: RecordTableController;
  /**
   * What the bar offers besides the count (D39): `all`, the default, the
   * page size and the pages; `pages`, the pages alone — a dashboard's
   * record panel, whose page size is its view's; `none`, the count alone —
   * a board read with no controls (a static embed, D36).
   */
  controls?: 'all' | 'pages' | 'none';
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
export function RecordPagination({
  table,
  controls = 'all',
}: RecordPaginationProps) {
  const messages = useViewMessages();
  const sizeLabelId = useId();
  const windowId = useId();
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

  // How many pages the pager can reach, where that is knowable — the
  // kernel's count, from the size that ran and the source's window. It is
  // never the total over the draft's page size: while a new size is on its
  // way, the rows, the page and the total on screen are all the old size's.
  const pages = paged ? paging.pages : undefined;
  // The rows those pages hold, when the source's window stops them short of
  // the total: Wow over Elasticsearch refuses a page past row 10 000, so
  // "第 1 / 31205 页" offered 31 205 pages and served 500 of them.
  const reachable = paged ? paging.reachable : undefined;

  // Numbers go to the catalogue as numbers: it groups them the surface's
  // way (`formatMessage`), as it does every count on screen — 「共 624000
  // 条记录」 was a number the reader had to count the digits of.

  // Everything fits, so there is nowhere to go and no arrows are drawn (D12).
  // Two dead arrows were the honest version of the same fact and still cost
  // two tab stops and a line of chrome to say "no"; absence says it without
  // asking anybody to read it. Only where the page count is actually known:
  // a cursor source cannot tell a single page from the first of many, and
  // the `stranded` guard above keeps the way back on any page but the first.
  const onePage = pages === 1 && paged && paging.index <= 1;

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
    // The registry's frame and nothing else of it (decisions.md D16-5): a
    // `nav` with a name, which is what this bar was missing — it used to be
    // a bare `div`, so the one control cluster that moves a reader through
    // a result was not a landmark and had nothing to announce itself as.
    // The numbered links `Pagination` is usually made of are not here and
    // are not wanted: this source is paged or cursored, and how many pages
    // there are is often unknowable.
    <Pagination
      data-slot="record-pagination"
      aria-label={messages.label('label.pagination.nav')}
      // The window is part of what this bar is: a reader who lands on it
      // hears why the pages stop where they do before trying to go further.
      aria-describedby={reachable === undefined ? undefined : windowId}
      // One line while there is room for one, two when there is not. A row
      // that could not wrap put Next's right edge 24px past the result card
      // it sits in at a phone's width, and `justify-between` squeezed the
      // count instead: "4 records in all" broke over three lines and the
      // Chinese "共 4 条记录" broke mid-word. The sentence keeps itself
      // whole, the controls take the line below it, and `ml-auto` keeps
      // them at the end of whichever line they land on.
      className={cn(
        'text-muted-foreground mx-0 flex-wrap items-center justify-start gap-x-4 gap-y-2',
        TEXT_UI,
      )}
    >
      <span className="whitespace-nowrap">{count}</span>
      {/* One short line, only when the window cuts the pages short of the
          total — it says where they stop and what to do about the rest. It
          is not a warning: nothing failed, and the way on is the conditions
          above, not anything on this bar. */}
      {reachable !== undefined && (
        <span id={windowId} data-slot="record-pagination-window">
          {messages.label('label.pagination.window', {
            count: reachable,
          })}
        </span>
      )}

      {controls !== 'none' && (
        <PaginationContent className="ml-auto flex-wrap justify-end gap-2">
          {controls === 'all' && (
            <PaginationItem className="flex items-center gap-2">
              <span id={sizeLabelId}>
                {messages.label('label.pagination.page-size')}
              </span>
              <Select
                items={sizes.map(size => ({
                  value: size,
                  label: sizeLabel(size),
                }))}
                value={table.pageSize}
                onValueChange={value => {
                  // Picked out of the offered sizes rather than cast: the list
                  // is what the control was built from.
                  const next = sizes.find(size => size === value);
                  if (next !== undefined) table.setPageSize(next);
                }}
              >
                {/* Named by the words beside it rather than by an `aria-label`
                of its own, so the control announces what the row already
                reads — one label, not two that have to be kept in step. */}
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
            </PaginationItem>
          )}

          {/* A cursor source has no page numbers and no way back, so it shows
            neither rather than showing them dead. */}
          {paged && (
            <PaginationItem className="flex items-center gap-2">
              {pages === undefined
                ? messages.label('label.toolbar.page', {
                    index: paging.index,
                  })
                : messages.label('label.toolbar.page-of', {
                    index: paging.index,
                    pages,
                  })}
              {/* Only where there is an M to aim at (ruling Ⅷ). A cursor source
                cannot say how many pages there are, so a box asking for one
                would be asking for a number nobody — the runtime included —
                could check. And it is drawn on exactly the pages the two
                steps are (D12 Ⅶ): where everything fits there is nowhere to
                go, and a box whose only admissible answer is the page
                already on screen is a control that does nothing. */}
              {pages !== undefined && !onePage && (
                <PageInput
                  index={paging.index}
                  pages={pages}
                  settled={table.status !== 'loading'}
                  onGoTo={table.goTo}
                  {...(reachable === undefined
                    ? {}
                    : { describedBy: windowId })}
                />
              )}
            </PaginationItem>
          )}
          {!onePage && (
            // The two steps are one group, so they sit `SPACE.WITHIN` apart
            // inside the `SPACE.GROUPS` the list puts between its items.
            <PaginationItem className="flex items-center gap-1">
              {paged && (
                <IconButton
                  label={messages.label('label.toolbar.previous')}
                  variant="outline"
                  size="icon-sm"
                  disabled={paging.index <= 1}
                  onClick={table.previous}
                >
                  <ChevronLeftIcon />
                </IconButton>
              )}
              <IconButton
                label={messages.label('label.toolbar.next')}
                variant="outline"
                size="icon-sm"
                disabled={!table.hasNext}
                onClick={table.next}
              >
                <ChevronRightIcon />
              </IconButton>
            </PaginationItem>
          )}
        </PaginationContent>
      )}
    </Pagination>
  );
}

interface PageInputProps {
  /** The page the rows on screen came from. */
  index: number;
  /**
   * How many the pager can reach — the ceiling, and the reason this box
   * exists. Inside the source's window, where it declares one.
   */
  pages: number;
  /** Whether no query is in flight: the page asked for has landed or not. */
  settled: boolean;
  onGoTo(page: number): void;
  /** The window's line, when there is one: why the ceiling is where it is. */
  describedBy?: string;
}

/**
 * The page a reader means, said outright (D18 ruling Ⅷ).
 *
 * Paging was one step at a time and nothing else, so page 17 of 40 cost
 * sixteen queries and sixteen waits. Where the source reported a total there
 * has always been an M to check a number against, and `goTo` on the
 * controller has always been able to ask for it; what was missing was
 * somewhere to say it.
 *
 * **It sits beside the sentence rather than inside it.** `第 1 / 4 页` is
 * where the reader already looks for the page they are on, and splitting it
 * around a box would leave a screen reader reading half a sentence, an
 * unnamed control and then the other half. So the sentence stays whole and
 * says where the rows came from, and the box — named `跳到第…页` — says
 * where to go. It carries the landed page until it is typed in, which is
 * what makes the two read apart while an answer is pending: the sentence is
 * the page that ran, the box is the page asked for.
 *
 * **Committed on Enter or on leaving, never on a keystroke.** Every commit
 * is a query, and `4` is one keystroke on the way to `40`.
 */
function PageInput({
  index,
  pages,
  settled,
  onGoTo,
  describedBy,
}: PageInputProps) {
  const messages = useViewMessages();
  // What is half-typed is the browser's to keep, not React's: every
  // keystroke through `setState` would re-render the bar for a draft nobody
  // has asked for yet, and the value only ever has to be *read* — on Enter,
  // on leaving, and nowhere else. Same reason the pinned offsets and a
  // column being dragged are written straight to the DOM.
  const box = useRef<HTMLInputElement>(null);

  // Whatever the box held, the page that landed is the truth about where the
  // reader is: a refresh, a size change or the two steps all move it, and a
  // box left saying `7` beside `第 2 / 4 页` would be the one thing on this
  // bar that is not about the rows on screen.
  useEffect(() => {
    if (box.current) box.current.value = String(index);
  }, [index]);

  // The page this box asked for, until the answer settles. A jump that
  // failed leaves `index` where it was, so the effect above never ran and
  // the box went on saying `600` beside `第 1 / 500 页`. Once the query
  // settles, a box still holding the page it asked for goes back to the page
  // shown; one the reader has typed over since is theirs, so a refresh
  // landing mid-keystroke does not take the number out from under them.
  // While the answer is on its way the two read apart on purpose: the
  // sentence is the page that ran, the box the page asked for.
  const asked = useRef<string | null>(null);
  useEffect(() => {
    if (!settled) return;
    const node = box.current;
    if (node && node.value === asked.current) node.value = String(index);
    asked.current = null;
  }, [index, settled]);

  const commit = () => {
    const node = box.current;
    if (!node) return;
    const typed = node.value.trim();
    // Only digits are a page. `` / `abc` / `1.5` / `-2` are not asking for
    // one, so the box goes back to saying where the reader is rather than
    // guessing what they meant — `Number('')` is 0, and a blank box that
    // silently jumped to the first page would be the worst of the guesses.
    if (!/^\d+$/.test(typed)) {
      node.value = String(index);
      return;
    }
    // Clamped rather than refused: past the end the reader wants the end.
    const page = Math.min(pages, Math.max(1, Number(typed)));
    node.value = String(page);
    if (page === index) return;
    asked.current = node.value;
    onGoTo(page);
  };

  return (
    <Input
      ref={box}
      aria-label={messages.label('label.pagination.go-to')}
      aria-describedby={describedBy}
      // Digits on a phone, without `type="number"`: that one brings a
      // spinner this bar has no room for, and hands back an empty string
      // for `1e3` rather than something to refuse.
      inputMode="numeric"
      // Wide enough for the pages there are and no wider — a box for four
      // digits beside a four-page result reads as a field, not as a number.
      className="w-14 text-center"
      defaultValue={String(index)}
      onBlur={commit}
      onKeyDown={event => {
        if (event.key !== 'Enter') return;
        // The bar sits inside whatever the host wrapped it in; Enter here
        // means this box and not a form somewhere above it.
        event.preventDefault();
        commit();
      }}
    />
  );
}
