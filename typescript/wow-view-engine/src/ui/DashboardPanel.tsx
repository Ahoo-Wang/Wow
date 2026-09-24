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

import { useRef, useState, type ReactNode } from 'react';
import { cn } from 'cn';
import {
  InfoIcon,
  MousePointerClickIcon,
  TriangleAlertIcon,
} from 'lucide-react';
import {
  readingOrder,
  type ArrangeStep,
  type OrderStep,
} from '../dashboard/index.js';
import type { Issue } from '../model/index.js';
import type { DashboardPanelView } from '../react/index.js';
import { useAnalysisEditor } from '../react/index.js';
import { isRecordRuntime } from '../runtime/index.js';
import {
  AnalysisPanel,
  RecordPanel,
  presentationMark,
} from './dashboard/PanelBodies.js';
import { analysisIssueNamer } from './analysis/issueNames.js';
import { PanelHandle, PanelOrder } from './DashboardArrange.js';
import { ContentPanel } from './DashboardPanels.js';
import type { PanelCommands } from './dashboard/commands.js';
import type { PanelPress } from './dashboard/press.js';
import { hasMenu, PanelMenu, PanelTitleInput } from './dashboard/PanelMenu.js';
import { PanelExport } from './dashboard/PanelExport.js';
import { PanelUnavailable } from './PanelUnavailable.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import { BadgeTooltip, IconTooltip } from './IconButton.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';
import { Badge } from './components/badge.js';
import { FOCUS_INSET, PanelCard, ToneBadge } from './variants.js';
import { Button } from './components/button.js';
import { CardContent, CardHeader, CardTitle } from './components/card.js';

/** Where a panel title may sit in a page's outline — never above a page title. */
export type PanelHeadingLevel = 2 | 3 | 4 | 5 | 6;

/** What an untitled content panel is called: what kind of thing it holds. */
const CONTENT_NAMES: Readonly<Record<string, MessageKey>> = {
  heading: 'label.panel.kind.heading',
  markdown: 'label.panel.kind.markdown',
  image: 'label.panel.kind.image',
  links: 'label.panel.kind.links',
};

/**
 * What a panel is called wherever it has to be named — its heading, its
 * handles, the line that says where it landed.
 *
 * Its own title first. A view panel without one is named after the view it
 * shows, which is what its reader sees in it; a heading after the words it
 * says; a content panel after the kind of thing it holds. What is left — a
 * panel whose view could not be opened —
 * is named by where it stands on the board in reading order, counted from
 * one (`index`). Never its id: `panel.id` is a key in a config, and
 * 「orders-2」 is not a word anyone reading the board ever chose.
 */
export function panelName(
  panel: Pick<DashboardPanelView, 'title' | 'panel' | 'runtime'>,
  index: number,
  messages: MessageFormatters,
): string {
  if (panel.title) return panel.title;
  const shown = panel.runtime?.getSnapshot()?.title;
  if (shown) return shown;
  if (panel.panel.kind === 'heading' && panel.panel.content.trim())
    return panel.panel.content.trim();
  const kind = CONTENT_NAMES[panel.panel.kind];
  return kind
    ? messages.label(kind)
    : messages.label('label.panel.untitled', { index: index + 1 });
}

/**
 * Every panel's name by its id, each counted by its place in reading order
 * — rows top to bottom, each left to right — which is the order the grid
 * draws them in and the one a reader counts by.
 *
 * Two panels are never called the same by the board itself: a name the
 * board made up (`panelName` without a title) that another panel already
 * goes by is numbered in reading order — 「笔记」, 「笔记 2」 — so a handle,
 * a finding or a landing announced by name points at one panel. Titles the
 * author gave are theirs and left as they are, even when two agree; a
 * made-up name steps around them.
 */
export function panelNames(
  panels: readonly DashboardPanelView[],
  messages: MessageFormatters,
): Map<string, string> {
  const used = new Set(panels.flatMap(panel => panel.title || []));
  return new Map(
    readingOrder(panels).map((panel, index) => {
      if (panel.title) return [panel.id, panel.title];
      const name = panelName(panel, index, messages);
      let numbered = name;
      for (let n = 2; used.has(numbered); n += 1)
        numbered = messages.label('label.panel.numbered', { name, n });
      used.add(numbered);
      return [panel.id, numbered];
    }),
  );
}

export interface DashboardPanelProps {
  panel: DashboardPanelView;
  editable?: boolean;
  /**
   * What the panel is called on screen; the grid names every panel once
   * (`panelName`), counting an untitled one by its place among the others.
   * Left out, the panel names itself the same way, as the first.
   */
  name?: string;
  /** The title's heading level; `3`, under a workbench's `h2`, by default. */
  headingLevel?: PanelHeadingLevel;
  /**
   * Whether the title is drawn (on by default). Off — an embed whose page
   * says what each panel is (`withPanelTitles`) — the heading stays for a
   * screen reader and the header row goes when nothing else is in it. A
   * heading panel is its title, so it is drawn either way.
   */
  titled?: boolean;
  /**
   * One arrange command, when the layout may be edited; answers whether
   * the panel moved (`arrangePanel` over the whole board had somewhere to
   * put it).
   */
  onArrange?: (step: ArrangeStep) => boolean;
  /** Takes back the last steps an arranging by keyboard took: its Escape. */
  onArrangeCancel?: (steps: number) => void;
  /**
   * 「上移」／「下移」 in the one-column reading while the board is built
   * (D22 J): where the panel stands in the column, how many there are, and
   * the step. Left out everywhere else.
   */
  order?: { index: number; total: number; onMove(step: OrderStep): void };
  /**
   * Re-runs this panel alone. Given, a panel whose query failed offers it
   * as its retry (`DashboardController.refreshPanel`).
   */
  onRetry?: () => void;
  /**
   * What the panel's 「⋯」 menu offers (`panelCommands`); no menu without
   * it, nor when it offers nothing.
   */
  commands?: PanelCommands;
  /**
   * The board's filters that hold a value and do not reach this panel, by
   * their names on the bar (D22 F「不受此筛选影响」); said as a badge in the
   * header, so nobody reads the panel as narrowed by them.
   */
  unreached?: readonly string[];
  /** Under the body: the wiring strip while a filter is wired (D22 G). */
  footer?: ReactNode;
  /**
   * What a press on one of the panel's groups does (D22 H, I); nothing on
   * it is pressable without it.
   */
  press?: PanelPress;
  /**
   * The filter a press on this panel sets, by its name on the bar: said in
   * the header (「点击筛选「仓库」」), so a reader knows a press filters the
   * board rather than opening a menu.
   */
  pressesFilter?: string;
  onRenderFailure?: RenderFailureHandler;
}

/**
 * One framed panel: a title, its menu, the two arrange controls while the
 * board is built, a body.
 *
 * The title is a heading, one level under whatever names the board — the
 * view's own `h2` in a workbench (`ViewHeader`), the host's in an embed: a
 * reader who moves through the page by headings reaches each panel by its
 * name, which a styled `div` never let them do.
 *
 * A heading panel (`kind: 'heading'`) is that title and nothing else: a
 * section's name across the board, set larger and on no body, since its
 * words are already its title element (D22 A).
 */
export function DashboardPanel({
  panel,
  editable,
  name: given,
  headingLevel = 3,
  titled = true,
  onArrange,
  onArrangeCancel,
  order,
  onRetry,
  commands,
  unreached,
  footer,
  press,
  pressesFilter,
  onRenderFailure,
}: DashboardPanelProps) {
  const messages = useViewMessages();
  const name = given ?? panelName(panel, 0, messages);
  const Title: `h${PanelHeadingLevel}` = `h${headingLevel}`;
  const heading = panel.panel.kind === 'heading';
  const menuTrigger = useRef<HTMLButtonElement>(null);
  // The export window, mounted the first time it opens — a panel nobody
  // exports from runs no export hooks — and kept while it closes.
  const [exporting, setExporting] = useState<boolean | null>(null);
  // A finding names its dimensions, metrics and fields as the panel's screen
  // does, never by a program's key (`analysisIssueNamer`); over a record
  // panel or a content panel the editor is empty and names nothing.
  const nameIssue = analysisIssueNamer(
    useAnalysisEditor(panel.runtime),
    messages,
  );
  // A panel that runs and still has something to say shows its view and
  // wears the finding in its header. A broken one says why in its body,
  // where the view would have been; whatever else it has to say — a config
  // can carry a warning beside its error — still goes in the header, and
  // only the finding the body shows is left out, so nothing is said twice.
  const shown = bodyIssue(panel);
  const warnings = panel.issues
    .filter(found => found.severity === 'warning' && found !== shown)
    .map(nameIssue);
  const warned = warnings.length > 0;
  // What is true of the panel's answer and nothing is wrong with — the
  // groups its own limit left out — rides beside the title quietly: no
  // warning colour on the glyph, none on the panel's edge.
  const notes = panel.issues
    .filter(found => found.severity === 'note')
    .map(nameIssue);
  const menu = commands && hasMenu(commands) ? commands : undefined;
  // A look of its own, on purpose (D22 D): a reader comparing the panel with
  // the view in the workbench is told so.
  const look = presentationMark(panel, messages);
  // In the board's way out: a broken panel is replaced or removed from its
  // own body while the board is built (`PanelUnavailable`).
  const wayOut = commands?.remove && {
    replace: commands.replace,
    editContent: commands.editContent,
    remove: commands.remove,
  };
  // A title turned off is read, not seen; the row it stood in goes with it
  // when nothing else stands there.
  const untitled = !titled && !heading;
  const badged =
    (unreached !== undefined && unreached.length > 0) ||
    pressesFilter !== undefined ||
    Boolean(look);
  const bare =
    untitled &&
    !warned &&
    notes.length === 0 &&
    !(editable && onArrange) &&
    !order &&
    !commands?.renaming &&
    !badged &&
    !menu;
  return (
    <PanelCard
      data-slot="dashboard-panel"
      data-kind={panel.panel.kind === 'view' ? undefined : panel.panel.kind}
      data-warning={warned || undefined}
      className={cn(
        'h-full gap-2 overflow-hidden py-3',
        heading && 'justify-center',
      )}
    >
      <CardHeader
        data-untitled={untitled || undefined}
        className={cn('px-3', bare && 'sr-only')}
      >
        <CardTitle
          className={cn(
            'flex min-w-0 items-center gap-1',
            heading ? 'text-base' : 'text-sm',
          )}
        >
          {/*
            The marker is the package's own `IconTooltip`: the same string
            names the control and fills the tooltip, focus opens it, and a
            tap opens it too. The colour sits on the glyph rather than on
            the button — `text-warning` is what the marker means, and the
            vendored ghost variant keeps its own hover and focus colours.
          */}
          {warned && (
            <IconTooltip
              label={messages.issues(warnings)}
              render={
                <Button
                  data-slot="panel-warning"
                  variant="ghost"
                  size="icon-sm"
                />
              }
            >
              <TriangleAlertIcon className="text-warning" />
            </IconTooltip>
          )}
          {notes.length > 0 && (
            <IconTooltip
              label={messages.issues(notes)}
              render={
                <Button data-slot="panel-note" variant="ghost" size="icon-sm" />
              }
            >
              <InfoIcon className="text-muted-foreground" />
            </IconTooltip>
          )}
          {/*
            One handle, dragged by a pointer and arranged with by keyboard
            (V-02); only while the board is built (D22 A): outside that,
            nothing on a panel moves it.
          */}
          {editable && onArrange && (
            <PanelHandle
              title={name}
              onStep={onArrange}
              onCancel={steps => onArrangeCancel?.(steps)}
            />
          )}
          {order && (
            <PanelOrder
              title={name}
              index={order.index}
              total={order.total}
              onMove={order.onMove}
            />
          )}
          {commands?.renaming ? (
            <PanelTitleInput
              initial={
                panel.panel.kind === 'heading'
                  ? panel.panel.content
                  : (panel.title ?? name)
              }
              heading={heading}
              renaming={commands.renaming}
              returnTo={menuTrigger}
            />
          ) : (
            <Title
              data-slot="panel-title"
              className={cn('min-w-0 truncate', untitled && 'sr-only')}
            >
              {name}
            </Title>
          )}
          {/* A 24px target hung in the title's own line (`-my-1`): the
              menu is on every panel a reader sees, and a header grown by
              it would take its height from a metric card's two rows. */}
          {menu && (
            <span className="-my-1 ml-auto flex shrink-0">
              <PanelMenu
                name={name}
                commands={menu}
                triggerRef={menuTrigger}
                onExport={() => setExporting(true)}
              />
            </span>
          )}
        </CardTitle>
        {/* The title's line is the name's: a reader tells panels apart by
            it, so the badges beside it take a line of their own under it
            rather than squeezing it to nothing on a narrow panel (U-10). */}
        {badged && (
          <div
            data-slot="panel-badges"
            className="flex min-w-0 flex-wrap gap-1"
          >
            {unreached && unreached.length > 0 && (
              <ToneBadge
                data-slot="panel-not-reached"
                tone="warning"
                dot={false}
                className="max-w-full"
              >
                {messages.label('label.filters.not-reached', {
                  filters: unreached
                    .map(filter =>
                      messages.label('label.filters.name-quoted', {
                        name: filter,
                      }),
                    )
                    .join(messages.label('label.filter.join')),
                })}
              </ToneBadge>
            )}
            {pressesFilter !== undefined && (
              <BadgeTooltip
                note={messages.label('label.click.badge-note', {
                  filter: pressesFilter,
                })}
                render={
                  <Badge
                    data-slot="panel-click-filter"
                    variant="outline"
                    render={<button type="button" />}
                  />
                }
              >
                <MousePointerClickIcon data-icon="inline-start" />
                {messages.label('label.click.badge', { filter: pressesFilter })}
              </BadgeTooltip>
            )}
            {look && (
              <BadgeTooltip
                note={messages.label('label.panel.presentation.note')}
                render={
                  <Badge
                    data-slot="panel-presentation"
                    variant="secondary"
                    render={<button type="button" />}
                  />
                }
              >
                {look}
              </BadgeTooltip>
            )}
          </div>
        )}
      </CardHeader>
      {(!heading || panel.broken) && (
        <CardContent
          /*
            The body scrolls, and a region that scrolls has to be reachable
            by keyboard. A chart panel has nothing focusable in it at all —
            the chart is one `role="img"` — so the scroll container itself
            takes the focus, named by the panel it belongs to.
          */
          role="group"
          tabIndex={0}
          aria-label={name}
          className={cn('min-h-0 flex-1 overflow-auto px-3', FOCUS_INSET)}
        >
          {/* One boundary per panel: a markdown body or a row that throws
              takes this card's body and leaves the rest of the grid alone. */}
          <RenderBoundary
            name="panel"
            panelId={panel.id}
            resetKeys={[panel.runtime?.id ?? null]}
            onFailure={onRenderFailure}
          >
            <PanelBody
              panel={panel}
              onRetry={onRetry}
              wayOut={wayOut}
              press={press}
              headingLevel={headingLevel}
            />
          </RenderBoundary>
        </CardContent>
      )}
      {footer && <div className="px-3">{footer}</div>}
      {commands?.exportRows && exporting !== null && (
        <PanelExport
          key={commands.exportRows.id}
          runtime={commands.exportRows}
          name={name}
          open={exporting}
          onOpenChange={setExporting}
          returnTo={menuTrigger}
        />
      )}
    </PanelCard>
  );
}

/** The ways out a broken panel offers while the board is built. */
interface WayOut {
  replace?: () => void;
  editContent?: () => void;
  remove: () => void;
}

function PanelBody({
  panel,
  onRetry,
  wayOut,
  press,
  headingLevel,
}: {
  panel: DashboardPanelView;
  onRetry?: () => void;
  wayOut?: WayOut | false;
  press?: PanelPress;
  /** The panel title's level, which a note's own headings go under. */
  headingLevel: PanelHeadingLevel;
}) {
  // A panel the dashboard could not open, or one admission refused, says so
  // and leaves the rest alone. Content panels come through here too: a link
  // whose scheme was rejected must not reach the document because the rest of
  // the dashboard happened to be fine.
  if (panel.broken)
    return <PanelUnavailable issue={bodyIssue(panel)} {...(wayOut || {})} />;
  if (panel.panel.kind !== 'view')
    return <ContentPanel panel={panel.panel} headingLevel={headingLevel} />;
  if (!panel.runtime)
    return <PanelUnavailable issue={panel.issues[0]} {...(wayOut || {})} />;
  return isRecordRuntime(panel.runtime) ? (
    <RecordPanel runtime={panel.runtime} onRetry={onRetry} />
  ) : (
    <AnalysisPanel runtime={panel.runtime} onRetry={onRetry} press={press} />
  );
}

/**
 * The finding a broken panel's body shows as the reason it is out: its
 * first error, else the warning that came alone. A panel that runs shows
 * its view instead, and its findings all belong to the header.
 */
function bodyIssue(panel: DashboardPanelView): Issue | undefined {
  if (!panel.broken) return undefined;
  return (
    panel.issues.find(found => found.severity === 'error') ?? panel.issues[0]
  );
}
