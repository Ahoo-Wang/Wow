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
 * The four distances this package puts between things, and the one surface
 * it draws them on.
 *
 * Everything used to be `gap-2` or `gap-3`, which is why a workbench read as
 * one flat stack of rows: if the space between two blocks is the space
 * between two buttons, nothing is grouped and the eye has nothing to hold
 * on to. The scale is what makes grouping visible, so it is written down
 * once and read from here rather than typed at each call site.
 *
 * | Step      | Between                                    |
 * | --------- | ------------------------------------------ |
 * | `BLOCKS`  | the three blocks of the main column (16px)  |
 * | `ROWS`    | rows inside one block (12px)                |
 * | `GROUPS`  | control groups inside one row (8px)         |
 * | `WITHIN`  | controls inside one group (4px)             |
 */
export const SPACE = {
  BLOCKS: 'gap-4',
  ROWS: 'gap-3',
  GROUPS: 'gap-2',
  WITHIN: 'gap-1',
} as const;

/**
 * A block of the main column: the conditions, the result. The title bar is
 * not one of these — it is a banner, ruled off rather than boxed, because a
 * card around the thing that names the page is a card around the page.
 */
export const SURFACE = 'rounded-lg border border-border bg-card p-3';

/**
 * A row of mutually exclusive options, joined into one control.
 *
 * The house rule (`docs/design/ui/README.md`) is that a choice between
 * options is one control and never a row of separate buttons. A vendored
 * `ToggleGroup variant="outline"` gives every item its own border and then
 * puts a gap between them, which reads as three independent buttons that
 * happen to sit near each other. This closes the gap, drops the inner
 * seams and leaves one outer frame — applied at the call site, because
 * `ui/components/**` is upstream's and is not edited by hand.
 *
 * It is for **three short options or fewer**. An option that is a sentence,
 * or a fourth option, goes in a `Select` instead: a segmented control is
 * read all at once, and there is only so much that can be.
 */
export const SEGMENTED =
  'gap-0 [&>*]:rounded-none [&>*]:shadow-none [&>*:first-child]:rounded-l-md [&>*:last-child]:rounded-r-md [&>*+*]:-ml-px';
