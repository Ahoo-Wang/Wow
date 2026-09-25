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
import type { ViewPreset } from '../presets.js';
import type { ViewTheme } from '../ViewSurface.js';

/**
 * How far a reader may go with an embed (D22, D36). Either way nothing is
 * written: an embed never saves a view, a board or a preference — defining
 * views, building boards and saving them are the workbenches' (D36).
 *
 * - `static` — what the page shows, as its author saved it: the rows, the
 *   chart or the board and what they were fetched under; nothing on it
 *   filters, sorts, pages, redraws or leads anywhere. The default, since a
 *   business page shows what someone else set up (D24 Q21).
 * - `interactive` — the reader may look closer, for this viewing only:
 *   change a board's filters, sort by a header, page, switch an analysis
 *   between table and chart, press a group (the follow-up menu, a board's
 *   cross-filter), fill the screen where the host offers it (`expandable`)
 *   and open the view in the workbench. None of it is saved; the ways off
 *   the page go through the host's route (`onNavigate`).
 */
export type EmbedInteraction = 'static' | 'interactive';

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
   * a panel — in the interactive tier, with a route to go by (on by
   * default). The static tier never offers it.
   */
  openInWorkbench?: boolean;
  /**
   * The host's route: 在工作台中打开, the follow-up menu on a group and a
   * panel's destination go through it (`ViewNavigation`). The package
   * never touches the address; without it none of them exist.
   */
  onNavigate?(to: ViewNavigation): void;
  /** The mode, as `ViewSurface` takes it: follows the host when left out. */
  theme?: ViewTheme;
  /** A preset pinned on the surface and its popups (`ViewSurface`). */
  preset?: ViewPreset;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  className?: string;
  /**
   * 「铺满屏幕」 in the embed's first row, in the interactive tier (off by
   * default): the surface fills the screen in place, as a workbench's does
   * (`ViewExpandToggle`), and Escape or the same button puts it back. Off,
   * the embed grows no such control (D10); the static tier never offers it.
   */
  expandable?: boolean;
  /**
   * The surface this draws on, handed back.
   *
   * Without `expandable` an embed grows no control of its own for filling
   * the screen: a button floating over somebody's order page is chrome that
   * page did not ask for (D10). A host that wants the control in its own
   * chrome instead — or on a static embed — points `useViewExpansion` at
   * the element it gets here.
   */
  ref?: Ref<HTMLDivElement>;
  /**
   * Told when the embed fails to draw — a row action of the host's that
   * throws, for one. The embed shows a recoverable error state in place.
   */
  onRenderFailure?: RenderFailureHandler;
}
