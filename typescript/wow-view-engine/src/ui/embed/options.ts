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

import type { Ref } from 'react';
import type { ViewNavigation, ViewEngine } from '../../runtime/index.js';
import type { PanelHeadingLevel } from '../DashboardPanel.js';
import type { ViewMessages } from '../messages.js';
import type { RenderFailureHandler } from '../RenderBoundary.js';

/**
 * How far a reader may go with an embedded record or analysis view (D22):
 *
 * - `read-only` — what the page shows, as its author saved it: the rows or
 *   the chart and what they were fetched under; nothing on it reorders,
 *   pages, redraws or leads anywhere. The default, since a business page
 *   shows what someone already decided.
 * - `interactive` — the reader may look closer: sort by a header, page,
 *   switch an analysis between table and chart, open the follow-up menu on
 *   one of its groups, and open the view in the workbench. None of it is
 *   saved; the last two go through the host's route (`onNavigate`).
 */
export type EmbedInteraction = 'read-only' | 'interactive';

/**
 * How far a reader may go with an embedded dashboard (D22): the two a data
 * view has — interactive meaning the follow-up menu, cross-filtering and
 * opening the view behind a panel — and `editable`, where whoever may save
 * the board builds it in place (「编辑」, the edit bar, 「完成」 saves).
 */
export type DashboardEmbedInteraction = EmbedInteraction | 'editable';

/**
 * How tall an embed is.
 *
 * - `content` — as tall as what it shows, up to a cap, so a card on a
 *   business page holds it: a record table scrolls inside
 *   `--fve-record-table-max-h` (70vh), a chart keeps its own height, a
 *   dashboard is as tall as its rows. The default.
 * - `fill` — the container's height, the result taking what the rest leaves
 *   and scrolling inside it: a whole-page embed, a wall screen. The
 *   container must have a height to give.
 */
export type EmbedSize = 'content' | 'fill';

/** What both embeds take, the view or the board they show aside. */
export interface EmbedBaseProps {
  engine: ViewEngine;
  /** The saved view or board to show; a code-declared system one works too. */
  instanceId: string;
  /** How tall it is (`EmbedSize`); `content` by default. */
  size?: EmbedSize;
  /**
   * Whether its title is drawn, as a heading at `headingLevel` (off by
   * default): a page usually names what it embeds in its own words.
   */
  withTitle?: boolean;
  /**
   * The heading level of what the embed titles: its own title when drawn,
   * and a dashboard's panels one level under it — or at it, with no title.
   * `2` by default, under the host page's `h1`; only the host knows its
   * outline.
   */
  headingLevel?: PanelHeadingLevel;
  /**
   * Whether it refreshes itself on the interval its author saved (on by
   * default). Off, the timer never runs — a page that refreshes itself, or
   * one printed — and nothing on screen offers it back.
   */
  autoRefresh?: boolean;
  /**
   * Whether 在工作台中打开 is offered — the view itself, or the view behind
   * a panel — in the interactive and editable tiers, with a route to go by
   * (on by default). The read-only tier never offers it.
   */
  openInWorkbench?: boolean;
  /**
   * The host's route: 在工作台中打开, the follow-up menu on a group and a
   * panel's destination go through it (`ViewNavigation`). The package
   * never touches the address; without it none of them exist.
   */
  onNavigate?(to: ViewNavigation): void;
  /** Follows the host page when left out. */
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  className?: string;
  /**
   * The surface this draws on, handed back.
   *
   * An embed grows no control of its own for filling the screen: a button
   * floating over somebody's order page is chrome that page did not ask for
   * and cannot place (D10). What it owes a host that wants one is the means,
   * which is this: put the control where your own chrome is, and point
   * `useViewExpansion` at the element you get here.
   */
  ref?: Ref<HTMLDivElement>;
  /**
   * Told when the embed fails to draw — a row action of the host's that
   * throws, for one. The embed shows a recoverable error state in place.
   */
  onRenderFailure?: RenderFailureHandler;
}
