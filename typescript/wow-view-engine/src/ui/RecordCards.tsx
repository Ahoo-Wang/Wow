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
import type { NumberFormat, RecordData, RecordKey } from '../model/index.js';
import type {
  RecordCardField,
  RecordCardView,
  RecordTableController,
} from '../react/index.js';
import type { RecordRow } from '../record/index.js';
import { recordValue } from '../record/index.js';
import { RowActions } from './RowActions.js';
import { Checkbox } from './components/checkbox.js';
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from './components/card.js';
import { displayValue, formatNumber, type DisplayContext } from './display.js';
import { cellValue } from './record/cells.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import { cn } from 'cn';
import { TEXT_UI } from './layout.js';

export interface RecordCardsProps {
  table: RecordTableController;
  renderValue?(value: unknown): React.ReactNode;
  /**
   * What a host offers on one card, in a footer under its body. Same
   * contract as the table's: the row alone, because the caller holding the
   * runtime is the one that binds the action context.
   */
  rowActions?(row: RecordRow): React.ReactNode;
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
 */
export function RecordCards({
  table,
  renderValue,
  rowActions,
}: RecordCardsProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const card = table.card;
  // A host's renderer decides for itself; otherwise a value shows as it does
  // in the field's column — the same renderer, so a card and the table it
  // folds out of can never disagree about what a value says.
  const show = (value: unknown, field: RecordCardField) =>
    renderValue
      ? renderValue(value)
      : cellValue(value, field, messages, display);

  return (
    <div
      data-slot="record-cards"
      className={cn('grid gap-3', GRID[card.columns ?? 3])}
    >
      {table.rows.map(row => (
        <Card key={String(row.key)}>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Checkbox
                aria-label={messages.label('label.record.select', {
                  key: String(row.key),
                })}
                checked={table.isSelected(row.key)}
                onCheckedChange={() => table.toggle(row.key)}
              />
              {cardTitle(row.data, row.key, card, messages, display)}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-1">
            {card.image && (
              <CardImage src={recordValue(row.data, card.image)} />
            )}
            {card.fields.map(field => (
              <div key={field.field} className="flex items-baseline gap-2">
                <span className={cn('text-muted-foreground', TEXT_UI)}>
                  {field.label}
                </span>
                <span className="truncate text-sm">
                  {show(recordValue(row.data, field.field), field)}
                </span>
              </div>
            ))}
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
  );
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

function cardTitle(
  row: RecordData,
  key: RecordKey,
  card: RecordCardView,
  messages: MessageFormatters,
  display: DisplayContext,
): React.ReactNode {
  const value = card.title ? recordValue(row, card.title) : key;
  const field = card.titleField;
  return (
    (field && displayValue(value, field, display)) ??
    defaultValue(value, messages, field?.numberFormat, display.locale) ??
    String(key)
  );
}

function defaultValue(
  value: unknown,
  messages: MessageFormatters,
  format?: NumberFormat,
  locale?: string,
): React.ReactNode {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
      return formatNumber(value, format, locale);
    case 'bigint':
      return value.toString();
    case 'boolean':
      return messages.label(value ? 'label.value.yes' : 'label.value.no');
    case 'object':
      return value === null ? null : JSON.stringify(value);
    default:
      return null;
  }
}
