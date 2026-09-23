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
  noCompactor,
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
  arrangeLayout,
  PanelArrangeMenu,
  PanelGrip,
  PanelResizeHandle,
  type ArrangeStep,
} from './DashboardArrange.js';
import { ContentPanel } from './DashboardPanels.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import { IconTooltip } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';
import { Button } from './components/button.js';
import { Card, CardContent, CardHeader, CardTitle } from './components/card.js';
import {
  Empty,
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
  className?: string;
}

/**
 * The panels, placed.
 *
 * The grid library owns geometry and nothing else: a move or a resize comes
 * back as a placement, the controller turns it into an edit and an apply, and
 * everything inside a panel is the same component a full view would use.
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
  const placed = (next: Layout) => {
    if (editable) dashboard.place(next.map(toPlacement));
  };

  /** One keyboard command: the same edit and apply a gesture lands. */
  const arrange = (panelId: string, step: ArrangeStep) => {
    const panel = dashboard.panels.find(found => found.id === panelId);
    if (!panel) return;
    const next = arrangeLayout(panel.layout, step, dashboard.columns);
    if (!next) return;
    dashboard.place([{ id: panelId, ...next }]);
    setArranged(
      messages.label('label.panel.placed', {
        title: panel.title ?? panel.id,
        // Said as a reader counts them, from one.
        column: next.x + 1,
        row: next.y + 1,
        w: next.w,
        h: next.h,
      }),
    );
  };

  if (dashboard.panels.length === 0)
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

  const layout: Layout = dashboard.panels.map(panel => ({
    i: panel.id,
    ...panel.layout,
  }));

  return (
    <div
      ref={containerRef}
      data-slot="dashboard-grid"
      className={cn('w-full', className)}
    >
      <GridLayout
        width={width}
        layout={layout}
        gridConfig={{ cols: dashboard.columns, rowHeight }}
        // Dragging by the header alone leaves the panel body clickable.
        dragConfig={{ enabled: editable, handle: '[data-slot="panel-grip"]' }}
        resizeConfig={{
          enabled: editable,
          // The corner is a named, focusable control while the layout may
          // be edited, and nothing at all while it may not — upstream's
          // bare `span` has no name to give and no key to answer.
          handleComponent: (axis: ResizeHandleAxis, ref: Ref<HTMLElement>) =>
            editable ? (
              <PanelResizeHandle axis={axis} ref={ref} onStep={arrange} />
            ) : (
              <span
                ref={ref}
                aria-hidden="true"
                className={`react-resizable-handle react-resizable-handle-${axis}`}
              />
            ),
        }}
        compactor={noCompactor}
        onDragStop={placed}
        onResizeStop={placed}
      >
        {dashboard.panels.map(panel => (
          // The id is stamped on the item because the resize corner is
          // appended here by the library, outside anything we render, and
          // it has to be able to say which panel it was dropped into.
          <div key={panel.id} data-panel-id={panel.id} className="min-h-0">
            <DashboardPanel
              panel={panel}
              editable={editable}
              columns={dashboard.columns}
              onArrange={step => arrange(panel.id, step)}
              onRenderFailure={onRenderFailure}
            />
          </div>
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

function toPlacement(item: Layout[number]) {
  return { id: item.i, x: item.x, y: item.y, w: item.w, h: item.h };
}

export interface DashboardPanelProps {
  panel: DashboardPanelView;
  editable?: boolean;
  /** Columns the grid places in; what the arrange commands run up against. */
  columns?: number;
  /** One arrange command, when the layout may be edited. */
  onArrange?: (step: ArrangeStep) => void;
  onRenderFailure?: RenderFailureHandler;
}

/**
 * One framed panel: a title, the two arrange controls when the layout is
 * editable, a body.
 */
export function DashboardPanel({
  panel,
  editable,
  columns = DASHBOARD_GRID_COLUMNS,
  onArrange,
  onRenderFailure,
}: DashboardPanelProps) {
  const messages = useViewMessages();
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
              <PanelGrip title={panel.title ?? panel.id} onStep={onArrange} />
              <PanelArrangeMenu
                title={panel.title ?? panel.id}
                layout={panel.layout}
                columns={columns}
                onStep={onArrange}
              />
            </>
          )}
          {panel.title ?? panel.id}
        </CardTitle>
      </CardHeader>
      <CardContent
        /*
          The body scrolls, and a region that scrolls has to be reachable by
          keyboard. A record panel gets that for free from the controls in its
          rows; a chart panel has nothing focusable in it at all — the drawing
          is one `role="img"` now, not the tab stop recharts used to put on
          its `<svg>` — so the scroll container itself takes the focus, named
          by the panel it belongs to.
        */
        role="group"
        tabIndex={0}
        aria-label={panel.title ?? panel.id}
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
          <PanelBody panel={panel} />
        </RenderBoundary>
      </CardContent>
    </Card>
  );
}

function PanelBody({ panel }: { panel: DashboardPanelView }) {
  // A panel the dashboard could not open, or one admission refused, says so
  // and leaves the rest alone. Content panels come through here too: a link
  // whose scheme was rejected must not reach the document because the rest of
  // the dashboard happened to be fine.
  if (panel.broken) return <Unavailable issue={bodyIssue(panel)} />;
  if (panel.panel.kind !== 'view') return <ContentPanel panel={panel.panel} />;
  if (!panel.runtime) return <Unavailable issue={panel.issues[0]} />;
  return isRecordRuntime(panel.runtime) ? (
    <RecordPanel runtime={panel.runtime} />
  ) : (
    <AnalysisPanel runtime={panel.runtime} />
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

function Unavailable({ issue }: { issue: Issue | undefined }) {
  const messages = useViewMessages();
  return (
    <Empty data-slot="panel-unavailable" className="p-4">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <UnplugIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.panel.unavailable')}</EmptyTitle>
        <EmptyDescription>
          {issue
            ? messages.issue(issue)
            : messages.label('label.panel.unavailable-hint')}
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
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
 */
function RecordPanel({ runtime }: { runtime: RecordViewRuntime }) {
  const table = useRecordTable(runtime);
  if (table.status === 'error')
    return <PanelFailed error={table.error ?? undefined} />;
  if (table.loading && table.rows.length === 0)
    return <Skeleton className="h-24 w-full" />;
  return <RecordTable table={table} selectable={false} scrolls={false} />;
}

function AnalysisPanel({ runtime }: { runtime: DataViewRuntime }) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;

  if (state?.query.status === 'error')
    return <PanelFailed error={state.query.error} />;
  if (!view) return <Skeleton className="h-24 w-full" />;
  return view.chart ? (
    <AnalysisChart
      data={view.chart}
      spec={analysis.chart}
      columns={view.schema ?? view.columns}
      className="h-full"
    />
  ) : (
    <AnalysisTable view={view} />
  );
}

/** One panel's failed query: reported here, while the others keep running. */
function PanelFailed({ error }: { error: Issue | undefined }) {
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
    </Empty>
  );
}
