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

import { columnTitle, displayValue, valueText } from './display.js';
import { useViewMessages } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';
import type { AnalysisView } from '../analysis/index.js';
import { AnalysisEmpty } from './analysis/EmptyResult.js';
import type { OnPick } from './charts/family.js';
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
  /**
   * Makes the rows pressable: a row opens the follow-up menu on the group
   * it is. This is the keyboard's path to the menu the chart's marks open
   * with a pointer (F10): a row takes focus and opens on Enter or Space.
   */
  onPick?: OnPick;
}

/**
 * The aggregation as a table: groups first, then metrics, with the totals row
 * from its own ungrouped query rather than from summing what is on screen.
 */
export function AnalysisTable({ view, onPick }: AnalysisTableProps) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // A group key or an ANY shows as its field's values do; the rest, and
  // anything the field's kind has nothing to say about, as before.
  const show = (value: unknown, column: AnalysisView['columns'][number]) =>
    displayValue(value, column, display) ??
    valueText(value, messages, column.numberFormat, display.locale);
  if (view.rows.length === 0) return <AnalysisEmpty />;

  return (
    // No scrollport of its own: the vendored `Table` already renders one
    // (`data-slot="table-container"`, `overflow-x-auto`), and a second box
    // around it is a scrollport that never scrolls — the inner one reaches
    // its scrollWidth first, so the outer never has anything to move, while
    // anything inside that wants to resolve against "the thing that
    // scrolls" resolves against the wrong one (which is why `RecordTable`
    // goes the other way and takes the vendored container *out* of the way
    // rather than adding to it). This div is the slot other code finds the
    // table by, and nothing else.
    <div data-slot="analysis-table">
      <Table>
        <TableHeader>
          <TableRow>
            {view.columns.map(column => (
              <TableHead
                key={column.alias}
                style={column.width ? { width: column.width } : undefined}
              >
                {columnTitle(column, messages)}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {view.rows.map((row, index) => (
            <TableRow
              key={index}
              data-pickable={onPick ? '' : undefined}
              tabIndex={onPick ? 0 : undefined}
              aria-haspopup={onPick ? 'menu' : undefined}
              className={onPick ? 'cursor-pointer' : undefined}
              onClick={
                onPick ? event => onPick(row, event.currentTarget) : undefined
              }
              onKeyDown={
                onPick
                  ? event => {
                      if (event.key !== 'Enter' && event.key !== ' ') return;
                      event.preventDefault();
                      onPick(row, event.currentTarget);
                    }
                  : undefined
              }
            >
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
