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

import { SigmaIcon } from 'lucide-react';
import { displayValue, valueText } from './display.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import type { AnalysisView } from '../analysis/index.js';
import {
  Empty,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from './components/table.js';

export interface AnalysisTableProps {
  view: AnalysisView;
}

/**
 * The aggregation as a table: groups first, then metrics, with the totals row
 * from its own ungrouped query rather than from summing what is on screen.
 */
export function AnalysisTable({ view }: AnalysisTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // A group key or an ANY shows as its field's values do; the rest, and
  // anything the field's kind has nothing to say about, as before.
  const show = (value: unknown, column: AnalysisView['columns'][number]) =>
    displayValue(value, column, display) ??
    valueText(value, messages, column.numberFormat);
  if (view.rows.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <SigmaIcon />
          </EmptyMedia>
          <EmptyTitle>{messages.label('label.analysis.empty')}</EmptyTitle>
        </EmptyHeader>
      </Empty>
    );
  }

  return (
    <div data-slot="analysis-table" className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            {view.columns.map(column => (
              <TableHead
                key={column.alias}
                style={column.width ? { width: column.width } : undefined}
              >
                {column.label}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {view.rows.map((row, index) => (
            <TableRow key={index}>
              {view.columns.map(column => (
                <TableCell key={column.alias}>
                  {show(row[column.alias], column)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
        {view.totals && (
          <TableFooter>
            <TableRow>
              {view.columns.map((column, index) => (
                <TableCell key={column.alias}>
                  {index === 0 && column.role === 'group'
                    ? messages.label('label.summary.total')
                    : show(view.totals?.[column.alias], column)}
                </TableCell>
              ))}
            </TableRow>
          </TableFooter>
        )}
      </Table>
    </div>
  );
}
