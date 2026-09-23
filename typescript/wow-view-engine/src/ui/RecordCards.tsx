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
import type { RecordTableController } from '../react/index.js';
import type { RecordCardField, RecordRow } from '../record/index.js';
import { recordValue } from '../record/index.js';
import { useOpenRows } from './record/openRows.js';
import { RowActions } from './RowActions.js';
import { RangeHint, RowCheckbox } from './record/RowCheckbox.js';
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from './components/card.js';
import {
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemTitle,
} from './components/item.js';
import { RowItem } from './RowItem.js';
import { CardSummaries } from './record/CardSummaries.js';
import { cellValue } from './record/cells.js';
import { EmptyResult } from './record/EmptyResult.js';
import { SkeletonCards } from './record/SkeletonCards.js';
import { useSummaries } from './record/useSummaries.js';
import type { RecordCell } from './RecordTable.js';
import { valueText } from './display.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import { cn } from 'cn';
import { FOCUS_CARD } from './variants.js';

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
  /** Whether the query that matched nothing carried conditions of its own. */
  hasConditions?: boolean;
  /** The one way out of an empty result; see `EmptyResult`. */
  onEmptyAction?(): void;
}

/**
 * Class per column count, written out rather than interpolated: Tailwind
 * scans the source for whole class names, and a built name reaches no
 * stylesheet.
 */
const GRID: Record<1 | 2 | 3 | 4, string> = {
  1: 'grid-cols-1',
  2: 'sm:grid-cols-2',
  3: 'sm:grid-cols-2 lg:grid-cols-3',
  4: 'sm:grid-cols-2 lg:grid-cols-4',
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
  hasConditions = false,
  onEmptyAction,
}: RecordCardsProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const card = table.card;
  const summaries = useSummaries(table.summaries, table.rows);
  // A grid read in reading order: every arrow is the next or the last card.
  const grid = useRef<HTMLDivElement>(null);
  const opening = useOpenRows(grid, onOpen, 'row', 'column');
  const rangeId = useId();
  const render =
    renderCell ??
    (found => cellValue(found.value, found.column, messages, display, 'card'));
  // The column a card field stands in for: what a host's renderer reads
  // is the same shape the table hands it, with the parts a card never has
  // at their "no" values.
  const cell = (row: RecordRow, field: RecordCardField): RecordCell => {
    const value = recordValue(row.data, field.field);
    // A field with no declared kind — a controller built by hand — reads
    // by what the value is, so a boolean still says yes rather than true.
    const kind = field.kind ?? kindOf(value);
    return {
      column: { ...field, kind, cell: field.cell ?? kind, sortable: false },
      row: row.data,
      key: row.key,
      value,
    };
  };

  // The same three gates the table keeps, for the same reasons it gives.
  if (!table.hasResult && table.status !== 'loading') return null;
  if (table.status === 'loading' && table.rows.length === 0)
    return <SkeletonCards fields={card.fields.length} />;
  if (table.status === 'success' && table.rows.length === 0)
    return (
      <EmptyResult
        title={emptyTitle}
        description={emptyDescription}
        hasConditions={hasConditions}
        onAction={onEmptyAction}
      />
    );

  return (
    <>
      <div
        ref={grid}
        data-slot="record-cards"
        className={cn('grid gap-3 p-3', GRID[card.perRow ?? 3])}
      >
        {table.rows.map(row => (
          <Card
            key={String(row.key)}
            className={cn(onOpen && ['cursor-pointer', FOCUS_CARD])}
            {...opening.row(row)}
          >
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                {selectable && (
                  <RowCheckbox table={table} row={row} hintId={rangeId} />
                )}
                {/* Read as its field reads it, through the same renderer as
                    the body: a status that titles a card wears the badge it
                    wears in its column, and a link is a link. The row key
                    alone names a card whose title field the definition has
                    dropped. */}
                {card.titleField
                  ? (render(cell(row, card.titleField)) ?? String(row.key))
                  : titleOf(row, card.title, messages, display.locale)}
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-1">
              {card.image && (
                <CardImage src={recordValue(row.data, card.image)} />
              )}
              {/* A card is a row folded out, so its body is drawn with the
                  same `Item` recipe the lists are (decisions.md D16-3): one
                  reading per row, the field's name beside the value it
                  belongs to. `role="listitem"` is said out loud because the
                  group says `role="list"` and these rows are `div`s. */}
              <ItemGroup className="gap-0">
                {card.fields.map(field => (
                  <RowItem
                    key={field.field}
                    role="listitem"
                    // The field's name is a label, not a sentence: `TEXT_UI`
                    // under the value's `text-sm`, which is the rung every
                    // other chrome label in this package sits on.
                    description="label"
                    data-slot="card-field"
                    data-field={field.field}
                    // `group/row` for the same reason the table's rows carry
                    // it: a card field is the row a cell asks about when it
                    // only offers something under the pointer (`CopyButton`).
                    className="group/row gap-2 px-0 py-0"
                  >
                    <ItemContent className="min-w-0 flex-row items-baseline gap-2">
                      <ItemDescription className="shrink-0">
                        {field.label}
                      </ItemDescription>
                      <ItemTitle className="min-w-0">
                        {render(cell(row, field))}
                      </ItemTitle>
                    </ItemContent>
                  </RowItem>
                ))}
              </ItemGroup>
            </CardContent>
            {/* A card has no column to pin actions to, so they sit under a
                rule at its foot — the same buttons, the same order. */}
            {rowActions && (
              <CardFooter className="flex justify-end gap-1 border-t pt-2">
                <RowActions>{rowActions(row)}</RowActions>
              </CardFooter>
            )}
          </Card>
        ))}
      </div>
      {opening.hintId && (
        <span id={opening.hintId} className="sr-only">
          {messages.label('label.record.detail.hint')}
        </span>
      )}
      {selectable && <RangeHint id={rangeId} />}
      {summaries.length > 0 && <CardSummaries rows={summaries} />}
    </>
  );
}

/** The kind a value reads as when its field declares none. */
function kindOf(value: unknown): string {
  switch (typeof value) {
    case 'boolean':
      return 'boolean';
    case 'number':
    case 'bigint':
      return 'number';
    default:
      return 'string';
  }
}

/**
 * A row's image, when it holds one. The alt text is empty on purpose: the
 * card's title already names the record, and repeating it would make a
 * screen reader say it twice.
 */
function CardImage({ src }: { src: unknown }) {
  if (typeof src !== 'string' || src.length === 0) return null;
  return (
    <img src={src} alt="" className="h-32 w-full rounded-md object-cover" />
  );
}

/**
 * The title of a card whose title field the definition no longer declares:
 * the value in the catalogue's words where the row has one, else the key.
 * `validateRecord` reports the field separately; this only keeps the card
 * named meanwhile.
 */
function titleOf(
  row: RecordRow,
  title: string,
  messages: MessageFormatters,
  locale: string | undefined,
): React.ReactNode {
  const text = valueText(
    title ? recordValue(row.data, title) : row.key,
    messages,
    undefined,
    locale,
  );
  return text === '' ? String(row.key) : text;
}
