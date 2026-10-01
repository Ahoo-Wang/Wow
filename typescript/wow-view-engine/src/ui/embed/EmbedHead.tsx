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

import { Children, useRef, type ReactNode } from 'react';
import { ArrowUpRightIcon } from 'lucide-react';
import { cn } from 'cn';
import type { RefreshController } from '../../react/index.js';
import type { ViewNavigation } from '../../runtime/index.js';
import { Button } from '../components/button.js';
import { readingTime } from '../kit/display.js';
import { TEXT_UI } from '../kit/layout.js';
import { RefreshControl } from '../workbench/RefreshControl.js';
import { useSurfaceDisplay } from '../kit/ViewSurface.js';
import type { PanelHeadingLevel } from '../dashboard/DashboardPanel.js';
import { useSay, useViewMessages } from '../kit/MessagesProvider.js';
import { useViewExpansion, ViewExpandToggle } from '../kit/ViewExpansion.js';
import { Truncated } from '../kit/Truncated.js';

/**
 * An embed's first row, when it has one: its title as a heading at the
 * level the host's outline calls for — and under it, where handed one, the
 * words that say what the numbers are read as (D70) — and on the right
 * what the host switched on — 在工作台中打开, the export, 「铺满屏幕」.
 * Nothing at all when there is neither: an embed is its result, and a row
 * of chrome nobody asked for is what it exists not to add (D10).
 */
export function EmbedHead({
  title,
  caption,
  headingLevel,
  inset,
  children,
}: {
  /** The title to draw; left out, none. */
  title?: string | undefined;
  /** Under the title, where there is one (`EmbeddedDashboard.caption`). */
  caption?: ReactNode;
  headingLevel: PanelHeadingLevel;
  /**
   * How far it stands in from the edge, in pixels: a board's, in line with
   * its panels (`BOARD_INSET`); none for a view, whose result runs to the
   * edge.
   */
  inset?: number;
  /** The controls on the right. */
  children?: ReactNode;
}) {
  // Children the host did not switch on arrive as `false`: an array of
  // them is no control, and must not draw an empty row that the surface's
  // gap then doubles.
  const controls = Children.toArray(children).length > 0;
  const say = useSay();
  if (title === undefined && !controls) return null;
  const Title: `h${PanelHeadingLevel}` = `h${headingLevel}`;
  return (
    <div
      data-slot="embed-head"
      className="fve:flex fve:flex-wrap fve:items-center fve:gap-2"
      style={inset === undefined ? undefined : { paddingInline: inset }}
    >
      {title !== undefined && (
        <div
          data-slot="embed-heading"
          className="fve:flex fve:min-w-0 fve:flex-col fve:gap-1"
        >
          <Truncated
            as={Title}
            data-slot="embed-title"
            className="fve:min-w-0 fve:text-base fve:font-semibold"
            text={say(title)}
          />
          {caption != null && caption !== false && (
            <div
              data-slot="embed-caption"
              className={cn('fve:text-muted-foreground', TEXT_UI)}
            >
              {caption}
            </div>
          )}
        </div>
      )}
      {controls && (
        <div
          data-slot="embed-actions"
          className="fve:ml-auto fve:flex fve:shrink-0 fve:items-center fve:gap-2"
        >
          {children}
        </div>
      )}
    </div>
  );
}

/**
 * 在工作台中打开, for an embedded view: the saved view itself under the
 * page's narrowing, in its own field names, handed to the host's route —
 * the same `{ kind: 'view' }` a dashboard panel's menu hands over. What the
 * reader did here (a sort, a page, a search) is not carried: the workbench
 * opens the view as its author saved it.
 */
export function OpenInWorkbench({
  to,
  onNavigate,
}: {
  to: ViewNavigation;
  onNavigate(to: ViewNavigation): void;
}) {
  const messages = useViewMessages();
  return (
    <Button
      data-slot="embed-open"
      variant="outline"
      size="sm"
      onClick={() => onNavigate(to)}
    >
      <ArrowUpRightIcon data-icon="inline-start" />
      {messages.label('label.panel.open')}
    </Button>
  );
}

/**
 * 「铺满屏幕」 on an embed (`EmbedBaseProps.expandable`, D36): the surface
 * the button stands on fills the screen in place — the same
 * `useViewExpansion` a workbench's title bar holds, pointed at the embed's
 * own `.fve-root` through the button itself — and Escape or the button
 * again puts it back. The control is inside the surface, so it stays on
 * screen while the surface fills it and the surface's own exit stays
 * hidden.
 */
export function EmbedExpand() {
  return useEmbedExpand(true).toggle;
}

/**
 * `EmbedExpand` held by the caller, for an embed that decides where the
 * button stands (`EmbeddedDashboard`: its first row, else the end of the
 * filter bar or the tabs, D10) and what it draws while the screen is its
 * (D70: a title and the caption). The expansion lives with the caller, so
 * the button moving between rows — the filter bar giving way as the board
 * narrows — never ends it. No button while `enabled` is off.
 */
export function useEmbedExpand(enabled: boolean): {
  toggle: ReactNode;
  expanded: boolean;
} {
  const toggleRef = useRef<HTMLButtonElement>(null);
  const expansion = useViewExpansion(toggleRef, toggleRef, enabled);
  return {
    toggle: enabled ? (
      <ViewExpandToggle expansion={expansion} ref={toggleRef} />
    ) : null,
    expanded: enabled && expansion.expanded,
  };
}

/**
 * How fresh an embedded board's numbers are, where the host asked
 * (`withRefresh`): 「更新于 10:32」 — when the panels on screen were read,
 * the earliest of them — and, in the interactive tier, the workbench's
 * refresh button beside it, the same control and the same spinner, without
 * the interval menu: how often the board renews itself is its author's and
 * the host's `autoRefresh`. Refreshing asks the source again and writes
 * nothing (D36). The static tier has no controls, so it reads the time
 * alone.
 */
export function EmbedFreshness({
  readAt,
  now,
  refresh,
  busy,
}: {
  /** When the numbers on screen were read (`DashboardController.readAt`). */
  readAt: number | null;
  /** The runtime's clock, which `readAt` is on. */
  now(): Date;
  /** The press, in a tier with controls; left out, the time alone. */
  refresh?: RefreshController;
  /** Whether a refresh would only replace requests already out. */
  busy: boolean;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  return (
    <>
      {readAt !== null && (
        <time
          data-slot="embed-read-at"
          dateTime={new Date(readAt).toISOString()}
          className={cn('fve:text-muted-foreground fve:tabular-nums', TEXT_UI)}
        >
          {messages.label('label.refresh.read-at', {
            time: readingTime(new Date(readAt), now(), display),
          })}
        </time>
      )}
      {refresh && (
        <RefreshControl
          refresh={refresh}
          variant="outline"
          choose={false}
          busy={busy}
        />
      )}
    </>
  );
}
