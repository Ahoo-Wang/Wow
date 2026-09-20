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

/**
 * One column of a rendered table, found by its header the way a reader finds
 * it, so an assertion names the column rather than its position.
 */
export function readColumn(table: HTMLElement, header: string): string[] {
  const index = columnIndex(table, header);
  return [...((table as HTMLTableElement).tBodies[0]?.rows ?? [])].map(row =>
    cellText(row, index),
  );
}

/** The column headers a reader sees, left to right, empty ones left out. */
export function readHeaders(table: HTMLElement): string[] {
  const cells = (table as HTMLTableElement).tHead?.rows[0]?.cells ?? [];
  return [...cells]
    .map(cell => cell.textContent?.trim() ?? '')
    .filter(text => text !== '');
}

/**
 * The cell under a header in the row summarising everything the conditions
 * match. A record table shows the page beside it and says which is which on
 * the row, so the scope is read rather than the position; an analysis table
 * has one totals row and no scope to name.
 */
export function readTotal(table: HTMLElement, header: string): string {
  return readSummary(table, header, 'total');
}

/** The same cell in the row summarising the rows on screen. */
export function readPage(table: HTMLElement, header: string): string {
  return readSummary(table, header, 'page');
}

function readSummary(
  table: HTMLElement,
  header: string,
  scope: 'page' | 'total',
): string {
  const foot = (table as HTMLTableElement).tFoot;
  const row =
    foot?.querySelector<HTMLTableRowElement>(`tr[data-scope="${scope}"]`) ??
    (scope === 'total' ? foot?.rows[0] : undefined);
  if (!row) throw new Error(`The table has no ${scope} summary row.`);
  return cellText(row, columnIndex(table, header));
}

/**
 * The amount a CNY cell shows, whatever the host's locale prints around it:
 * `¥1,920.00`, `CN¥1,920.00` and `SUM ¥1,920.00` all read 1920. Every locale
 * prints CNY with two decimals, so the digits alone carry the number.
 */
export function amountOf(text: string): number {
  return Number(text.replace(/\D/g, '')) / 100;
}

/**
 * A header carries more than its name — a sort mark, and its place in the
 * sort once several columns order the table — so the column's own label is
 * read from the element that holds it, and the whole cell only where there
 * is no such element to read.
 */
function columnIndex(table: HTMLElement, header: string): number {
  const cells = (table as HTMLTableElement).tHead?.rows[0]?.cells ?? [];
  const index = [...cells].findIndex(cell => {
    const label = cell.querySelector('[data-slot="column-label"]');
    return (label ?? cell).textContent?.trim() === header;
  });
  if (index < 0) throw new Error(`No column is headed "${header}".`);
  return index;
}

function cellText(row: HTMLTableRowElement, index: number): string {
  return row.cells[index]?.textContent?.trim() ?? '';
}
