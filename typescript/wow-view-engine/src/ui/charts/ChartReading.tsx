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

import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../components/table.js';
import { useViewMessages } from '../MessagesProvider.js';
import type { ChartReading } from './reading.js';

/**
 * The chart's numbers as a table, for whoever cannot see the marks.
 *
 * It is the same registry `Table` that `AnalysisTable` draws with, filled
 * from the chart projection rather than the row projection, so it says what
 * this layout actually put on screen. `sr-only` takes the whole thing out of
 * flow, so it costs the drawing no space and changes nothing that is looked
 * at — and it stays a real table, because a table is how a screen reader
 * walks numbers by row and column.
 */
export function ChartReadingTable({ reading }: { reading: ChartReading }) {
  const messages = useViewMessages();
  if (reading.rows.length === 0) return null;
  return (
    <div
      data-slot="chart-reading"
      // The registry's table brings a scroll container, and inside a one-pixel
      // `sr-only` box everything overflows it: a browser then reports a
      // scrollable region no one can reach by keyboard, which is a real
      // finding — for a table anyone looks at. This one nobody does, so the
      // container is told not to scroll rather than given a tab stop.
      className="sr-only [&_[data-slot=table-container]]:overflow-visible"
    >
      <Table>
        <TableCaption>
          {messages.label('label.chart.reading', { name: reading.name })}
        </TableCaption>
        <TableHeader>
          <TableRow>
            {reading.header.map((cell, column) => (
              <TableHead key={column} scope="col">
                {cell}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {reading.rows.map((row, index) => (
            <TableRow key={index}>
              {row.map((cell, column) =>
                // The first cell names the row — a category, a stage, a date
                // — so it is that row's header rather than one more number.
                column === 0 ? (
                  <TableHead key={column} scope="row">
                    {cell}
                  </TableHead>
                ) : (
                  <TableCell key={column}>{cell}</TableCell>
                ),
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
