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
import { Checkbox } from './components/checkbox.js';
import { Card, CardContent, CardHeader, CardTitle } from './components/card.js';

export interface RecordCardsProps {
  table: RecordTableController;
  /** Field shown as each card's title; the row key when left out. */
  title?: string;
  renderValue?(value: unknown): React.ReactNode;
}

/**
 * The same result as cards. Which layout is showing is part of the saved
 * config, and both keep their own settings, so switching back and forth
 * loses nothing.
 */
export function RecordCards({
  table,
  title,
  renderValue = defaultValue,
}: RecordCardsProps) {
  return (
    <div
      data-slot="record-cards"
      className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3"
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
              {cardTitle(row.data, row.key, title)}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-1">
            {table.columns.map(column => (
              <div key={column.field} className="flex items-baseline gap-2">
                <span className="text-muted-foreground text-xs">
                  {column.label}
                </span>
                <span className="truncate text-sm">
                  {renderValue(row.data[column.field])}
                </span>
              </div>
            ))}
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

function cardTitle(
  row: RecordData,
  key: RecordKey,
  field?: string,
): React.ReactNode {
  const value = field ? row[field] : key;
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
