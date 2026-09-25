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
 * The one size under the body text: 13px, from `--_fve-text-ui` in `styles.css`.
 *
 * There used to be two of them within 0.8px of each other — `text-xs` (12)
 * on the group labels, column headers, badges, pagination and the toolbar's
 * hint, and the registry's `sm` control size (`text-[0.8rem]`, 12.8) on the
 * sidebar's view items and every small button. Two sizes that close are not
 * a rank, they are one size drawn twice; and 12.8px lands off the pixel
 * grid, which is why 中文 at that size looked blurry. Both became this, so
 * the surface has three rungs — 13 / 14 (`text-sm`) / 16 — and the step
 * between two of them is visible.
 *
 * The line height comes with it: the class replaced (`text-xs`) carried one,
 * and a font size on its own would have left each label with whatever its
 * parent happened to say.
 *
 * `text-xs` stays where the text is genuinely a rank below this one — the
 * labels drawn *inside* a chart, where density is the point, and the
 * `AND`/`OR` code a menu item wears in front of the sentence it stands for.
 */
export const TEXT_UI = 'text-[length:var(--_fve-text-ui)] leading-[1.125rem]';

/**
 * A heading the keyboard is *sent* to when a panel changes level
 * (`tabIndex={-1}`), and never tabbed to: it is where the reader lands, not a
 * control. It wears no focus ring — the browser drew one round the panel's
 * title each time the panel changed, a box around words that cannot be
 * pressed (2026-09-23 audit). The next Tab goes to the first control after
 * it, which rings as every control does; a screen reader reads the heading
 * it landed on either way.
 */
export const LANDING_HEADING = 'text-base font-semibold outline-none';

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
 * The width a fixed-width dashboard is held to, centred (D31): 1200px.
 *
 * Chosen from the grid rather than the screen. The board is 24 columns with
 * a 10px gap, so at 1200px one column is about 40px and the sizes a panel
 * starts at (`defaultPanelSize`) come out at widths each reads well at: a
 * metric card, six columns, about 290px — its number and its trend line
 * with room, not a quarter of a wall screen; a chart, twelve, about 585px —
 * a month of daily bars, labelled; a note, twelve, a line of text short
 * enough to read across. It is wider than `md` (768px), so a fixed board
 * never falls into the one-column reading any earlier than a full one; and
 * it is about what a workbench's main column is on a laptop, so the two
 * widths differ only where a full board would stretch — a wide monitor, a
 * wall screen — which is where a fixed one is asked for.
 */
export const FIXED_BOARD_WIDTH = 1200;

/**
 * The filter tray (decisions.md D12): a wash of the muted colour and no
 * border. The conditions inside it are the bordered things; a border around
 * a row of bordered pills was the box-in-box the layout was rebuilt to lose.
 */
export const TRAY = 'rounded-lg bg-muted/40 p-3';
