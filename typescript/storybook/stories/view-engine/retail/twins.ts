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
import { expect, screen, waitFor } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';

/* The small reads the retail twins share (docs/scenarios.md 6.1). */

export const label = (
  key: keyof typeof zhCN,
  params: Record<string, string> = {},
) =>
  Object.entries(params).reduce<string>(
    (text, [name, value]) => text.replace(`{${name}}`, value),
    zhCN[key],
  );

/** A panel's body, by its title (the region a reader tabs into). */
export const panelOf = (name: string) => screen.getByRole('group', { name });

/** The number a metric card shows, as it reads. */
export const valueOf = (name: string) =>
  panelOf(name).querySelector('[data-slot="metric-value"]')?.textContent;

/** No panel on the tab refuses its view, fails, or is out. */
export async function noPanelOut(canvasElement: HTMLElement) {
  await waitFor(
    () =>
      expect(
        canvasElement.querySelectorAll('[data-slot="dashboard-panel"]').length,
      ).toBeGreaterThan(0),
    { timeout: 10_000 },
  );
  await expect(
    canvasElement.querySelector(
      '[data-slot="panel-failed"], [data-slot="panel-unavailable"], [data-slot="panel-warning"]',
    ),
  ).toBeNull();
}

/** The reading table a chart keeps for a screen reader. */
export async function findReading(
  panel: HTMLElement,
): Promise<HTMLTableElement> {
  return waitFor(() => {
    const table = panel.querySelector<HTMLTableElement>(
      '[data-slot="chart-reading"] table',
    );
    if (!table) throw new Error('No reading table yet.');
    return table;
  });
}

/** Every body row of a table, each as its cells' text. */
export function rowsOf(table: HTMLTableElement): string[][] {
  return [...table.tBodies[0].rows].map(row =>
    [...row.cells].map(cell => cell.textContent?.trim() ?? ''),
  );
}
