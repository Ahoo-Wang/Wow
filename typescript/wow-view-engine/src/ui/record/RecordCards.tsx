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
import { useId, useRef } from 'react';
import type { RecordTableController } from '../../react/index.js';
import type { RecordRow } from '../../record/index.js';
import { useOpenRows } from './openRows.js';
import { RangeHint } from './RowCheckbox.js';
import { CardSummaries } from './CardSummaries.js';
import { EmptyResult } from './EmptyResult.js';
import type { EmptyWayOut } from './emptyWayOut.js';
import { RecordCard } from './RecordCard.js';
import { SkeletonCards } from './SkeletonCards.js';
import { useSummaries } from './useSummaries.js';
import type { RecordCell } from './RecordTable.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { cn } from 'cn';

export interface RecordCardsProps {
  table: RecordTableController;
  /**
   * Renders one value of a card; the default reads it as the field says.
   * The same contract as the table's, handed the same `column` for the same
   * field: a card is a row folded out, and one renderer serves both.
   */
  renderCell?(cell: RecordCell): React.ReactNode;
  /**
   * Whether cards can be picked. Same contract as the table's, and the same
   * default: a card is a row folded out, so a surface that offers nothing to
   * do with a selection must not grow one back by switching layout.
   */
  selectable?: boolean;
  /**
   * What a host offers on one card, in a footer under its body. Same
   * contract as the table's: the row alone, because the caller holding the
   * runtime is the one that binds the action context.
   */
  rowActions?(row: RecordRow): React.ReactNode;
  /**
   * Opens a card's detail: a press on the card, or Enter/Space on the card
   * the keyboard is on (`record/openRows.ts`).
   */
  onOpen?(row: RecordRow): void;
  /** The empty result's wording, when the host has its own. */
  emptyTitle?: string;
  emptyDescription?: string;
  /** Which way out an empty result offers; see `RecordTable`. */
  emptyWayOut?: EmptyWayOut;
  /** The one way out of an empty result; see `EmptyResult`. */
  onEmptyAction?(): void;
}

/**
 * Class per column count, written out rather than interpolated: Tailwind
 * scans the source for whole class names, and a built name reaches no
 * stylesheet.
 */
const GRID: Record<1 | 2 | 3 | 4, string> = {
  1: 'fve:grid-cols-1',
  2: 'fve:sm:grid-cols-2',
  3: 'fve:sm:grid-cols-2 fve:lg:grid-cols-3',
  4: 'fve:sm:grid-cols-2 fve:lg:grid-cols-4',
};

/**
 * The same result as cards, drawn from the card half of the saved config.
 *
 * Which layout is showing is part of that config and both halves are stored,
 * so switching back and forth loses nothing — and a card is not the table
 * narrowed: its title, its body fields and its image are its own.
 *
 * What it says when there is nothing to draw is the table's answer too
 * (D18 V): nothing at all before a first result, skeleton cards while the
 * first one is on its way, the empty result with its one way out when the
 * query matched nothing, and the summary lines under the cards whenever
 * the table would have drawn its footer.
 */
export function RecordCards({
  table,
  renderCell,
  selectable = true,
  rowActions,
  onOpen,
  emptyTitle,
  emptyDescription,
  emptyWayOut = 'add',
  onEmptyAction,
}: RecordCardsProps) {
  const messages = useViewMessages();
  const card = table.card;
  const summaries = useSummaries(table.summaries, table.rows, table.paging);
  // A grid read in reading order: every arrow is the next or the last card.
  const grid = useRef<HTMLDivElement>(null);
  const opening = useOpenRows(grid, onOpen, 'row', 'column');
  const rangeId = useId();
  // The same three gates the table keeps, for the same reasons it gives.
  if (!table.hasResult && table.status !== 'loading') return null;
  if (table.status === 'loading' && table.rows.length === 0)
    return <SkeletonCards fields={card.fields.length} />;
  if (table.status === 'success' && table.rows.length === 0)
    return (
      <EmptyResult
        title={emptyTitle}
        description={emptyDescription}
        wayOut={emptyWayOut}
        onAction={onEmptyAction}
      />
    );

  return (
    <>
      <div
        ref={grid}
        data-slot="record-cards"
        className={cn(
          'fve:bg-canvas fve:grid fve:gap-3 fve:p-3',
          GRID[card.perRow ?? 3],
        )}
      >
        {table.rows.map(row => (
          <RecordCard
            key={String(row.key)}
            table={table}
            row={row}
            renderCell={renderCell}
            selectable={selectable}
            rangeId={rangeId}
            rowActions={rowActions}
            openable={onOpen !== undefined}
            rowProps={opening.row(row)}
          />
        ))}
      </div>
      {opening.hintId && (
        <span id={opening.hintId} className="fve:sr-only">
          {messages.label('label.record.detail.hint')}
        </span>
      )}
      {selectable && <RangeHint id={rangeId} />}
      {summaries.length > 0 && <CardSummaries rows={summaries} />}
    </>
  );
}
