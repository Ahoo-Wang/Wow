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
 * How a result says the rows on screen answer an older question than the one
 * on its way (`answeringAnew`): kept, and faded, for as long as the answer
 * takes. One recipe for every result the engine draws — an analysis, a
 * record table, its cards and its kanban, in a workbench, an embed and a
 * board's panel — so a slow answer looks like one still working wherever it
 * is, and never like the new rows.
 *
 * It was the analysis result's alone (2026-09-23 audit), and a record table
 * or a board's panel kept its old rows at full strength under conditions
 * that had already changed. A refresh of the same question is not this: its
 * rows are the answer to what is being asked, and they stay as they are.
 *
 * `STALE` is the fade, worn by the element that carries `data-stale`: it
 * must be one that generates a box, since `opacity` paints nothing on a
 * `display: contents` element.
 */
export const STALE =
  'fve:data-[stale]:opacity-70 fve:data-[stale]:transition-opacity';

/**
 * The fade's attribute and `aria-busy`, for an element that holds the rows
 * and nothing that speaks: a table, a grid of cards, a panel's body. A block
 * that says 「正在查询」 from inside itself — a result block with its live
 * region — writes `data-stale` alone: `aria-busy` on it, and the sentence is
 * never read.
 */
export function staleProps(stale: boolean | undefined): {
  'data-stale'?: true;
  'aria-busy'?: true;
} {
  return stale ? { 'data-stale': true, 'aria-busy': true } : {};
}
