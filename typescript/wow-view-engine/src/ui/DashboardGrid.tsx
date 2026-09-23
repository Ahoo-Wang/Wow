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

import { useState, type Ref } from 'react';
import { cn } from 'cn';
import GridLayout, {
  getBreakpointFromWidth,
  useContainerWidth,
  type Layout,
  type ResizeHandleAxis,
} from 'react-grid-layout';
import {
  InfoIcon,
  LayoutDashboardIcon,
  TriangleAlertIcon,
  UnplugIcon,
} from 'lucide-react';
import type { AnalysisView } from '../analysis/index.js';
import {
  arrangeLayout,
  readingOrder,
  stackedLayout,
  type ArrangeStep,
} from '../dashboard/index.js';
import { DASHBOARD_GRID_COLUMNS, type Issue } from '../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../react/index.js';
import {
  useAnalysisEditor,
  useRecordTable,
  useViewRuntime,
} from '../react/index.js';
import {
  isRecordRuntime,
  type DataViewRuntime,
  type RecordViewRuntime,
} from '../runtime/index.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisTable } from './AnalysisTable.js';
import {
  PanelArrangeMenu,
  PanelGrip,
  PanelGridItem,
  PanelResizeHandle,
} from './DashboardArrange.js';
import { ContentPanel } from './DashboardPanels.js';
import { PanelUnavailable } from './PanelUnavailable.js';
import { useGridPlacement } from './gridPlacement.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import { QueryStrip } from './StatusStrip.js';
import { IconTooltip } from './IconButton.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';
import { Button } from './components/button.js';
import { Card, CardContent, CardHeader, CardTitle } from './components/card.js';
import {
  Empty,
  EmptyContent,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  EmptyDescription,
} from './components/empty.js';
import { RecordTable } from './RecordTable.js';
import { Skeleton } from './components/skeleton.js';

export interface DashboardGridProps {
  dashboard: DashboardController;
  /** Whether panels may be dragged and resized; read-only by default. */
  editable?: boolean;
  /** Pixel height of one grid row. */
  rowHeight?: number;
  /** Told when one panel's body fails to draw; the others keep drawing. */
  onRenderFailure?: RenderFailureHandler;
  /**
   * The heading level of each panel's title: one under whatever names the
   * board. `3` under a workbench's own `h2`; an embed passes what its host's
   * page calls for, since only the host knows its outline.
   */
  headingLevel?: PanelHeadingLevel;
  className?: string;
}

/** Where a panel title may sit in a page's outline — never above a page title. */
export type PanelHeadingLevel = 2 | 3 | 4 | 5 | 6;

/**
 * The panels, placed.
 *
 * The grid library draws the gesture and nothing else: where a moved or
 * resized panel lands, and what it pushes out of the way, is the kernel's
 * (`useGridPlacement`), the drop reaches `dashboard.place`, which applies that
 * placement alone, and everything inside a panel is the same component a
 * full view would use.
 *
 * Only a gesture writes geometry back. The library also reports a layout it
 * computed itself — on mount, and whenever the props change — and applying
 * that would dirty a dashboard nobody touched, so those reports are not
 * listened to. Panels sit where the config put them, not where a compactor
 * would move them: the kernel admits `layout` as authored, and the screen
 * must show what was admitted.
 */
export function DashboardGrid({
  dashboard,
  editable = false,
  rowHeight = 80,
  className,
  onRenderFailure,
  headingLevel = 3,
}: DashboardGridProps) {
  // The grid needs a pixel width and the container only knows it once it is
  // on screen, so the measuring is the library's own hook rather than a
  // second `ResizeObserver` written here: it is the same observer the grid
  // would have used, it survives an environment without `ResizeObserver`,
  // and it coalesces a burst of resizes into one frame.
  const { containerRef, width } = useContainerWidth();
  const messages = useViewMessages();
  // What the last keyboard command did, said once. A pointer sees the panel
  // move under it; a keyboard has only the layout, which is not on screen,
  // so the new place is read out. It starts empty, so opening a dashboard
  // announces nothing.
  const [arranged, setArranged] = useState('');
  // The last width the container could be drawn at. A hidden container
  // measures 0, and that is not a phone: the grid keeps the panels at their
  // last width then, and the breakpoint stays where it was with them.
  const [drawnWidth, setDrawnWidth] = useState(width);
  if (width > 0 && width !== drawnWidth) setDrawnWidth(width);
  // A column too narrow to hold a panel gives up its grid for one column —
  // chosen by the library's own breakpoint reading, over the width the grid
  // is laid out at, not by a media query on the panels.
  const narrow = getBreakpointFromWidth(BREAKPOINTS, drawnWidth) === 'narrow';
  // One column is a reading of the layout, not a layout: nothing placed in
  // it could be written back, so neither the gestures nor the keyboard
  // commands are on offer there.
  const arranging = editable && !narrow;
  const placement = useGridPlacement(dashboard.place);

  // Reading order — rows top to bottom, each left to right — over the layout
  // as stored. The panels are rendered in it too, so the tab order follows
  // what is on screen rather than the order the config happens to list them.
  const panels = readingOrder(dashboard.panels);
  const byId = new Map(dashboard.panels.map(panel => [panel.id, panel]));
  const names = panelNames(dashboard.panels, messages);

  /** One keyboard command: the same placement a gesture lands. */
  const arrange = (panelId: string, step: ArrangeStep) => {
    const panel = byId.get(panelId);
    // The one-column reading is never a placement to write back.
    if (!panel || !arranging) return;
    const next = arrangeLayout(panel.layout, step, dashboard.columns);
    if (!next) return;
    dashboard.place(panelId, next);
    setArranged(
      messages.label('label.panel.placed', {
        title: names.get(panelId) ?? '',
        // Said as a reader counts them, from one.
        column: next.x + 1,
        row: next.y + 1,
        width: count(messages, next.w, COLUMNS),
        height: count(messages, next.h, ROWS),
      }),
    );
  };

  if (dashboard.panels.length === 0) return <DashboardEmpty />;

  // Below `md`, the kernel's one-column reading of the stored layout; the
  // stored layout itself everywhere else.
  const boxes = panels.map(panel => ({ id: panel.id, ...panel.layout }));
  const layout: Layout = (narrow ? stackedLayout(boxes) : boxes).map(
    ({ id, ...box }) => ({ i: id, ...box }),
  );

  return (
    <div
      ref={containerRef}
      data-slot="dashboard-grid"
      data-narrow={narrow || undefined}
      className={cn('w-full', className)}
    >
      <GridLayout
        // A new grid on either side of the breakpoint: the library holds the
        // layout in state of its own and catches up with a new one an effect
        // later, so for one frame it drew the wide layout's six-column panels
        // in a one-column grid — six times the width of the screen.
        key={narrow ? 'narrow' : 'wide'}
        width={width}
        layout={layout}
        gridConfig={{ cols: narrow ? 1 : dashboard.columns, rowHeight }}
        // Dragging by the header alone leaves the panel body clickable.
        dragConfig={{
          enabled: arranging,
          handle: '[data-slot="panel-grip"]',
        }}
        resizeConfig={{
          enabled: arranging,
          // The corner is a named, focusable control while the layout may
          // be edited, and nothing at all while it may not — upstream's
          // bare `span` has no name to give and no key to answer.
          handleComponent: (axis: ResizeHandleAxis, ref: Ref<HTMLElement>) =>
            arranging ? (
              <PanelResizeHandle axis={axis} ref={ref} onStep={arrange} />
            ) : (
              <span
                ref={ref}
                aria-hidden="true"
                className={`react-resizable-handle react-resizable-handle-${axis}`}
              />
            ),
        }}
        compactor={placement.compactor}
        // The gestures are off in the one-column reading, and so are the
        // callbacks that would hand a placement to `place`: nothing drawn in
        // that column is a layout the config could hold.
        {...(arranging
          ? {
              onDragStart: placement.onDragStart,
              onDragStop: placement.onDragStop,
              onResizeStart: placement.onResizeStart,
              onResizeStop: placement.onResizeStop,
            }
          : {})}
      >
        {panels.map(panel => (
          // The library appends the resize corner to this item, after the
          // panel; the item says which panel it holds to whatever lands in
          // it, so the corner is named after its own panel.
          <PanelGridItem
            key={panel.id}
            panelId={panel.id}
            name={names.get(panel.id) ?? ''}
            className="min-h-0"
          >
            <DashboardPanel
              panel={panel}
              name={names.get(panel.id)}
              headingLevel={headingLevel}
              editable={arranging}
              columns={dashboard.columns}
              onArrange={step => arrange(panel.id, step)}
              onRetry={() => dashboard.refreshPanel(panel.id)}
              onRenderFailure={onRenderFailure}
            />
          </PanelGridItem>
        ))}
      </GridLayout>
      {/* One region for the whole grid rather than one per panel: only one
          panel is ever being placed, and the rest would be a dozen empty
          regions for a reader to walk past. */}
      <span aria-live="polite" className="sr-only">
        {arranged}
      </span>
    </div>
  );
}

/**
 * Where the grid gives up its columns: a container narrower than Tailwind's
 * `md` (768px) lays the panels out in one. `getBreakpointFromWidth` picks the
 * widest breakpoint the width is *above*, so `wide` starts at 767 for 768 to
 * be the first width that has it.
 */
const BREAKPOINTS = { narrow: 0, wide: 767 } as const;

/**
 * A count said with its noun: 「1 column」, never 「1 columns」. The package
 * spells the singular as a key of its own rather than inflecting a word.
 */
function count(
  messages: MessageFormatters,
  value: number,
  [many, one]: readonly [MessageKey, MessageKey],
): string {
  return value === 1
    ? messages.label(one)
    : messages.label(many, { count: value });
}

const COLUMNS = ['label.panel.columns', 'label.panel.columns-one'] as const;
const ROWS = ['label.panel.rows', 'label.panel.rows-one'] as const;

/** What an untitled content panel is called: what kind of thing it holds. */
const CONTENT_NAMES: Readonly<Record<string, MessageKey>> = {
  markdown: 'label.panel.kind.markdown',
  image: 'label.panel.kind.image',
  links: 'label.panel.kind.links',
};

/**
 * What a panel is called wherever it has to be named — its heading, its
 * handles, the line that says where it landed.
 *
 * Its own title first. A view panel without one is named after the view it
 * shows, which is what its reader sees in it; a content panel after the kind
 * of thing it holds. What is left — a panel whose view could not be opened —
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
  const kind = CONTENT_NAMES[panel.panel.kind];
  return kind
    ? messages.label(kind)
    : messages.label('label.panel.untitled', { index: index + 1 });
}

/**
 * Every panel's name by its id, each counted by its place in reading order
 * — rows top to bottom, each left to right — which is the order the grid
 * draws them in and the one a reader counts by.
 */
export function panelNames(
  panels: readonly DashboardPanelView[],
  messages: MessageFormatters,
): Map<string, string> {
  return new Map(
    readingOrder(panels).map((panel, index) => [
      panel.id,
      panelName(panel, index, messages),
    ]),
  );
}

/**
 * A dashboard with nothing on it: what a dashboard is, and that this one
 * holds no panel yet.
 *
 * It promises nothing. There is no way to add a panel today, so an empty
 * state that said "add a saved view" pointed at a door that was not there
 * (U1). Adding panels is D22's batch B, and its 「添加」 goes in this
 * component, as an `EmptyContent` under the header — the one place a board
 * with no panels has room for a first action.
 */
function DashboardEmpty() {
  const messages = useViewMessages();
  return (
    <Empty data-slot="dashboard-empty">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <LayoutDashboardIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.dashboard.empty')}</EmptyTitle>
        <EmptyDescription>
          {messages.label('label.dashboard.empty-hint')}
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}

export interface DashboardPanelProps {
  panel: DashboardPanelView;
  editable?: boolean;
  /** Columns the grid places in; what the arrange commands run up against. */
  columns?: number;
  /**
   * What the panel is called on screen; the grid names every panel once
   * (`panelName`), counting an untitled one by its place among the others.
   * Left out, the panel names itself the same way, as the first.
   */
  name?: string;
  /** The title's heading level; `3`, under a workbench's `h2`, by default. */
  headingLevel?: PanelHeadingLevel;
  /** One arrange command, when the layout may be edited. */
  onArrange?: (step: ArrangeStep) => void;
  /**
   * Re-runs this panel alone. Given, a panel whose query failed offers it
   * as its retry (`DashboardController.refreshPanel`).
   */
  onRetry?: () => void;
  onRenderFailure?: RenderFailureHandler;
}

/**
 * One framed panel: a title, the two arrange controls when the layout is
 * editable, a body.
 *
 * The title is a heading, one level under whatever names the board — the
 * view's own `h2` in a workbench (`ViewHeader`), the host's in an embed: a
 * reader who moves through the page by headings reaches each panel by its
 * name, which a styled `div` never let them do.
 */
export function DashboardPanel({
  panel,
  editable,
  columns = DASHBOARD_GRID_COLUMNS,
  name: given,
  headingLevel = 3,
  onArrange,
  onRetry,
  onRenderFailure,
}: DashboardPanelProps) {
  const messages = useViewMessages();
  const name = given ?? panelName(panel, 0, messages);
  const Title: `h${PanelHeadingLevel}` = `h${headingLevel}`;
  // A panel that runs and still has something to say shows its view and
  // wears the finding in its header. A broken one says why in its body,
  // where the view would have been; whatever else it has to say — a config
  // can carry a warning beside its error — still goes in the header, and
  // only the finding the body shows is left out, so nothing is said twice.
  const shown = bodyIssue(panel);
  const warnings = panel.issues.filter(
    found => found.severity === 'warning' && found !== shown,
  );
  const warned = warnings.length > 0;
  // What is true of the panel's answer and nothing is wrong with — the
  // groups its own limit left out — rides beside the title quietly: no
  // warning colour on the glyph, none on the panel's edge.
  const notes = panel.issues.filter(found => found.severity === 'note');
  return (
    <Card
      data-slot="dashboard-panel"
      data-warning={warned || undefined}
      className="h-full gap-2 overflow-hidden py-3 data-[warning]:border-warning"
    >
      <CardHeader className="px-3">
        <CardTitle className="flex items-center gap-1 text-sm">
          {/*
            The marker went through `title`, which no keyboard and no touch
            screen ever opens, so what the warning actually said was reachable
            only by hovering a mouse over a 16px glyph. It is the package's
            own `IconTooltip` now: the same string names the control and fills
            the tooltip, focus opens it, and a tap opens it too. The colour
            sits on the glyph rather than on the button — `text-warning` is
            what the marker means, and the vendored ghost variant keeps its
            own hover and focus colours underneath it.
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
            The grip was decorative while dragging was a pointer gesture
            with no keyboard equivalent — naming it would have announced an
            affordance its user could not reach. It answers the arrow keys
            now, so it is a named control, and the menu beside it says the
            same commands in words for anyone who does not know the keys.
          */}
          {editable && onArrange && (
            <>
              <PanelGrip title={name} onStep={onArrange} />
              <PanelArrangeMenu
                title={name}
                layout={panel.layout}
                columns={columns}
                onStep={onArrange}
              />
            </>
          )}
          <Title data-slot="panel-title" className="min-w-0 truncate">
            {name}
          </Title>
        </CardTitle>
      </CardHeader>
      <CardContent
        /*
          The body scrolls, and a region that scrolls has to be reachable by
          keyboard. A record panel gets that for free from the controls in its
          rows; a chart panel has nothing focusable in it at all — the chart
          is one `role="img"` with nothing inside it that takes focus — so the
          scroll container itself takes the focus, named by the panel it
          belongs to.
        */
        role="group"
        tabIndex={0}
        aria-label={name}
        className="min-h-0 flex-1 overflow-auto px-3"
      >
        {/* One boundary per panel: a markdown body or a row that throws
            takes this card's body and leaves the rest of the grid alone. */}
        <RenderBoundary
          name="panel"
          panelId={panel.id}
          resetKeys={[panel.runtime?.id ?? null]}
          onFailure={onRenderFailure}
        >
          <PanelBody panel={panel} onRetry={onRetry} />
        </RenderBoundary>
      </CardContent>
    </Card>
  );
}

function PanelBody({
  panel,
  onRetry,
}: {
  panel: DashboardPanelView;
  onRetry?: () => void;
}) {
  // A panel the dashboard could not open, or one admission refused, says so
  // and leaves the rest alone. Content panels come through here too: a link
  // whose scheme was rejected must not reach the document because the rest of
  // the dashboard happened to be fine.
  if (panel.broken) return <PanelUnavailable issue={bodyIssue(panel)} />;
  if (panel.panel.kind !== 'view') return <ContentPanel panel={panel.panel} />;
  if (!panel.runtime) return <PanelUnavailable issue={panel.issues[0]} />;
  return isRecordRuntime(panel.runtime) ? (
    <RecordPanel runtime={panel.runtime} onRetry={onRetry} />
  ) : (
    <AnalysisPanel runtime={panel.runtime} onRetry={onRetry} />
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

/**
 * A record panel is a readout, not a worklist: the dashboard shows rows and
 * offers nothing to do with a pick — no toolbar, no row action, nothing that
 * reads the selection — so the table comes without its checkbox column.
 *
 * The panel is what scrolls here, so the table does not: its own scroll area
 * would be a box nothing ever scrolls, and the header and the summaries would
 * stay put against it while the panel moved them off the top.
 *
 * Nor does it hold its last column on the right. A panel is read where it
 * stands, and a narrow one overflows with only a few columns: the held end
 * then sits over the middle before anything has scrolled, covering the
 * column before it — and the pin cap (D17-4) keeps it, one column being
 * under half the port. The panel is the frame here; the key, if shown,
 * stays held on the left, where it covers nothing at rest.
 *
 * A refresh that fails over rows that are still good says so above them
 * rather than instead of them, the way the workbenches and `EmbeddedView`
 * do (`QueryStrip` with `stale`): a panel that emptied itself on a dropped
 * connection would lose what its reader was reading for no reason they
 * caused. Only a failure with nothing behind it takes the body.
 */
function RecordPanel({
  runtime,
  onRetry,
}: {
  runtime: RecordViewRuntime;
  onRetry?: () => void;
}) {
  const table = useRecordTable(runtime);
  const failed = table.status === 'error';
  if (failed && !table.hasResult)
    return <PanelFailed error={table.error ?? undefined} onRetry={onRetry} />;
  if (table.loading && table.rows.length === 0)
    return <Skeleton className="h-24 w-full" />;
  return (
    <>
      <QueryStrip error={failed ? table.error : null} stale onRetry={onRetry} />
      <RecordTable
        table={table}
        selectable={false}
        scrolls={false}
        holdEnd={false}
      />
    </>
  );
}

/** The chart or the table an analysis panel shows; stale as a record panel is. */
function AnalysisPanel({
  runtime,
  onRetry,
}: {
  runtime: DataViewRuntime;
  onRetry?: () => void;
}) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  const failed = state?.query.status === 'error';

  if (failed && !view)
    return <PanelFailed error={state.query.error} onRetry={onRetry} />;
  if (!view) return <Skeleton className="h-24 w-full" />;
  const body = view.chart ? (
    <AnalysisChart
      data={view.chart}
      spec={analysis.chart}
      columns={view.schema ?? view.columns}
      // Under the stale line the chart takes what is left of the panel.
      className={failed ? 'min-h-0 flex-1' : 'h-full'}
      cutShort={view.truncated || view.atLimit !== undefined}
    />
  ) : (
    <AnalysisTable view={view} />
  );
  if (!failed) return body;
  return (
    <div className="flex h-full flex-col gap-2">
      <QueryStrip error={state.query.error} stale onRetry={onRetry} />
      {body}
    </div>
  );
}

/**
 * One panel's failed query with no earlier result behind it: reported here,
 * while the others keep running, with the way to run this one again — the
 * board's refresh would re-run every panel to retry one.
 */
function PanelFailed({
  error,
  onRetry,
}: {
  error: Issue | undefined;
  onRetry?: () => void;
}) {
  const messages = useViewMessages();
  return (
    <Empty data-slot="panel-failed" className="p-4">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <UnplugIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.query.failed')}</EmptyTitle>
        <EmptyDescription>
          {error ? messages.issue(error) : undefined}
        </EmptyDescription>
      </EmptyHeader>
      {onRetry && (
        <EmptyContent>
          <Button variant="outline" size="sm" onClick={onRetry}>
            {messages.label('label.query.retry')}
          </Button>
        </EmptyContent>
      )}
    </Empty>
  );
}
