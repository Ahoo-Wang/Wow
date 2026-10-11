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
import type { RecordTableController } from '../../react/index.js';
import type { RecordCardField, RecordRow } from '../../record/index.js';
import { recordValue } from '../../record/index.js';
import type { OpenRowProps } from './openRows.js';
import { RowActions } from './RowActions.js';
import { RowCheckbox } from './RowCheckbox.js';
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from '../components/card.js';
import {
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemTitle,
} from '../components/item.js';
import { RowItem } from '../kit/RowItem.js';
import { cellValue } from './cells.js';
import type { RecordCell } from './RecordTable.js';
import { cellText, valueText } from '../kit/display.js';
import { inRowCurrency } from '../kit/currency.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../kit/MessagesProvider.js';
import { useSurfaceDisplay } from '../kit/ViewSurface.js';
import { cn } from 'cn';
import { CARD_LIFT, FOCUS_CARD } from '../kit/variants.js';

export interface RecordCardProps {
  /** The controller whose card layout, picking and range hint it draws. */
  table: RecordTableController;
  row: RecordRow;
  /** See `RecordCardsProps.renderCell`. */
  renderCell?(cell: RecordCell): React.ReactNode;
  /** Whether the card carries its pick box. */
  selectable: boolean;
  /** The range hint a pick box points at, when cards can be picked. */
  rangeId: string;
  /** See `RecordCardsProps.rowActions`. */
  rowActions?(row: RecordRow): React.ReactNode;
  /** Whether a press opens the card (`useOpenRows`). */
  openable: boolean;
  /** What makes the card a member of its surface's group (`useOpenRows`). */
  rowProps: OpenRowProps;
}

/**
 * One record as a card: its title in two lines, its body as one `Item` per
 * field, its image, and the host's actions at its foot. The card layout
 * lays these out in a grid (`RecordCards`) and the kanban in its lanes
 * (`RecordKanban`, D83): one record is one card in either.
 */
export function RecordCard({
  table,
  row,
  renderCell,
  selectable,
  rangeId,
  rowActions,
  openable,
  rowProps,
}: RecordCardProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const card = table.card;
  const render =
    renderCell ??
    (found =>
      cellValue(
        found.value,
        inRowCurrency(found.column, found.row),
        messages,
        display,
        'card',
      ));
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

  // The title as a line of text, for the hover that shows what two lines
  // cut short.
  const titleText = (row: RecordRow): string =>
    card.titleField
      ? cellText(
          recordValue(row.data, card.titleField.field),
          inRowCurrency(card.titleField, row.data),
          messages,
          display,
        ) || String(row.key)
      : titleOf(row, card.title, messages, display.locale);

  return (
    <Card
      key={String(row.key)}
      className={cn(CARD_LIFT, openable && ['fve:cursor-pointer', FOCUS_CARD])}
      {...rowProps}
    >
      <CardHeader>
        <CardTitle className="fve:flex fve:items-center fve:gap-2">
          {selectable && (
            <RowCheckbox table={table} row={row} hintId={rangeId} />
          )}
          {/* Read as its field reads it, through the same renderer as
              the body: a status that titles a card wears the badge it
              wears in its column, and a link is a link. The row key
              alone names a card whose title field the definition has
              dropped. */}
          {/* Up to two lines, then the rest one hover away: a processor
              name like OrderItemReservedTrackEventProcessor is longer
              than a card is wide, and clipped mid-word it named
              nothing. */}
          <span
            data-slot="card-title-text"
            className="fve:line-clamp-2 fve:min-w-0 fve:[overflow-wrap:anywhere]"
            title={titleText(row)}
          >
            {card.titleField
              ? (render(cell(row, card.titleField)) ?? String(row.key))
              : titleOf(row, card.title, messages, display.locale)}
          </span>
        </CardTitle>
      </CardHeader>
      <CardContent className="fve:flex fve:flex-col fve:gap-1">
        {card.image && <CardImage src={recordValue(row.data, card.image)} />}
        {/* A card is a row folded out, so its body is drawn with the
            same `Item` recipe the lists are (decisions.md D16-3): one
            reading per row, the field's name beside the value it
            belongs to. `role="listitem"` is said out loud because the
            group says `role="list"` and these rows are `div`s. */}
        <ItemGroup className="fve:gap-0">
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
              className="fve:group/row fve:gap-2 fve:px-0 fve:py-0"
            >
              <ItemContent className="fve:min-w-0 fve:flex-row fve:items-baseline fve:gap-2">
                <ItemDescription className="fve:shrink-0">
                  {messages.say(field.label)}
                </ItemDescription>
                {/* An identifier has no space to wrap at — a
                    function name like onInvoiceRegistrationCreated —
                    so it may break anywhere rather than run off the
                    card's edge. */}
                <ItemTitle className="fve:min-w-0 fve:[overflow-wrap:anywhere]">
                  {render(cell(row, field))}
                </ItemTitle>
              </ItemContent>
            </RowItem>
          ))}
        </ItemGroup>
      </CardContent>
      {/* A card has no column to pin actions to, so they sit under a
          rule at its foot — the same buttons, the same order — and at
          the foot of the tallest card in the row too (`mt-auto`), not
          wherever a shorter card's body ended. */}
      {rowActions && (
        <CardFooter className="fve:mt-auto fve:flex fve:justify-end fve:gap-1 fve:border-t fve:pt-2">
          <RowActions>{rowActions(row)}</RowActions>
        </CardFooter>
      )}
    </Card>
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
    <img
      src={src}
      alt=""
      className="fve:h-32 fve:w-full fve:rounded-md fve:object-cover"
    />
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
): string {
  const text = valueText(
    title ? recordValue(row.data, title) : row.key,
    messages,
    undefined,
    locale,
  );
  return text === '' ? String(row.key) : text;
}
