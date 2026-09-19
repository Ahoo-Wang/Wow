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

/** The totals row's cell under a header. */
export function readTotal(table: HTMLElement, header: string): string {
  const row = (table as HTMLTableElement).tFoot?.rows[0];
  if (!row) throw new Error('The table has no totals row.');
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

function columnIndex(table: HTMLElement, header: string): number {
  const cells = (table as HTMLTableElement).tHead?.rows[0]?.cells ?? [];
  const index = [...cells].findIndex(
    cell => cell.textContent?.trim() === header,
  );
  if (index < 0) throw new Error(`No column is headed "${header}".`);
  return index;
}

function cellText(row: HTMLTableRowElement, index: number): string {
  return row.cells[index]?.textContent?.trim() ?? '';
}
