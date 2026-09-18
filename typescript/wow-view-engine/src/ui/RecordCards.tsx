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
import type { RecordData, RecordKey } from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { recordValue } from '../record/index.js';
import { Checkbox } from './components/checkbox.js';
import { Card, CardContent, CardHeader, CardTitle } from './components/card.js';
import { cn } from 'cn';

export interface RecordCardsProps {
  table: RecordTableController;
  renderValue?(value: unknown): React.ReactNode;
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
  renderValue = defaultValue,
}: RecordCardsProps) {
  const card = table.card;

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
                aria-label={`Select ${String(row.key)}`}
                checked={table.isSelected(row.key)}
                onCheckedChange={() => table.toggle(row.key)}
              />
              {cardTitle(row.data, row.key, card.title)}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-1">
            {card.image && (
              <CardImage src={recordValue(row.data, card.image)} />
            )}
            {card.fields.map(field => (
              <div key={field.field} className="flex items-baseline gap-2">
                <span className="text-muted-foreground text-xs">
                  {field.label}
                </span>
                <span className="truncate text-sm">
                  {renderValue(recordValue(row.data, field.field))}
                </span>
              </div>
            ))}
          </CardContent>
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
  field: string,
): React.ReactNode {
  const value = field ? recordValue(row, field) : key;
  return defaultValue(value) ?? String(key);
}

function defaultValue(value: unknown): React.ReactNode {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
    case 'bigint':
      return value.toString();
    case 'boolean':
      return value ? 'Yes' : 'No';
    case 'object':
      return value === null ? null : JSON.stringify(value);
    default:
      return null;
  }
}
