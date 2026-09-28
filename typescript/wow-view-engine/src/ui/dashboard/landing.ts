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

/** The board a control is on: the grid's column, header and all. */
export function boardOf(from: Element | null | undefined): Element | null {
  return from?.closest('[data-slot="dashboard-grid"]') ?? null;
}

/** One filter's chip on the bar, by the filter's name. */
export function chipOf(
  board: Element | null,
  name: string,
): HTMLElement | undefined {
  return chipsOf(board).find(chip => chip.dataset.filter === name);
}

/** The filters' chips on the bar, in its order. */
export function chipsOf(board: Element | null): HTMLElement[] {
  return [
    ...(board?.querySelectorAll<HTMLElement>(
      '[data-slot="dashboard-filter"]',
    ) ?? []),
  ];
}

/** A chip's value control: what a reader edits the filter with. */
export function valueOf(chip: Element | null | undefined): Element | null {
  return chip?.querySelector('[data-slot="filter-value"]') ?? chip ?? null;
}

/** 「撤销」 on the edit bar: where a step that took its own control away lands. */
export function undoOf(board: Element | null): Element | null {
  return board?.querySelector('[data-slot="dashboard-undo"]') ?? null;
}

/** 「添加筛选」 on the edit bar. */
export function addFilterOf(board: Element | null): Element | null {
  return board?.querySelector('[data-slot="dashboard-add-filter"]') ?? null;
}
