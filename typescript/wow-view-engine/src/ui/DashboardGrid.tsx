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

import { useEffect, useRef, useState } from 'react';
import { cn } from 'cn';
import GridLayout, { noCompactor, type Layout } from 'react-grid-layout';
import {
  GripVerticalIcon,
  LayoutDashboardIcon,
  TriangleAlertIcon,
  UnplugIcon,
} from 'lucide-react';
import type { AnalysisView } from '../analysis/index.js';
import type { Issue } from '../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../react/index.js';
import {
  useAnalysisEditor,
  useRecordTable,
  useViewRuntime,
} from '../react/index.js';
import type { DataViewRuntime, RecordViewRuntime } from '../runtime/index.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisTable } from './AnalysisTable.js';
import { ContentPanel } from './DashboardPanels.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import { useViewMessages } from './MessagesProvider.js';
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
  const { ref, width } = useContainerWidth();
  const messages = useViewMessages();
  const placed = (next: Layout) => {
    if (editable) dashboard.place(next.map(toPlacement));
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
      ref={ref}
      data-slot="dashboard-grid"
      className={cn('w-full', className)}
    >
      <GridLayout
        width={width}
        layout={layout}
        gridConfig={{ cols: dashboard.columns, rowHeight }}
        // Dragging by the header alone leaves the panel body clickable.
        dragConfig={{ enabled: editable, handle: '[data-slot="panel-grip"]' }}
        resizeConfig={{ enabled: editable }}
        compactor={noCompactor}
        onDragStop={placed}
        onResizeStop={placed}
      >
        {dashboard.panels.map(panel => (
          <div key={panel.id} className="min-h-0">
            <DashboardPanel
              panel={panel}
              editable={editable}
              onRenderFailure={onRenderFailure}
            />
          </div>
        ))}
      </GridLayout>
    </div>
  );
}

function toPlacement(item: Layout[number]) {
  return { id: item.i, x: item.x, y: item.y, w: item.w, h: item.h };
}

export interface DashboardPanelProps {
  panel: DashboardPanelView;
  editable?: boolean;
  onRenderFailure?: RenderFailureHandler;
}

/** One framed panel: a title, a grip when the layout is editable, a body. */
export function DashboardPanel({
  panel,
  editable,
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
  return (
    <Card
      data-slot="dashboard-panel"
      data-warning={warned || undefined}
      className="h-full gap-2 overflow-hidden py-3 data-[warning]:border-warning"
    >
      <CardHeader className="px-3">
        <CardTitle className="flex items-center gap-1 text-sm">
          {warned && (
            <span
              data-slot="panel-warning"
              role="img"
              aria-label={messages.issues(warnings)}
              title={messages.issues(warnings)}
              className="text-warning"
            >
              <TriangleAlertIcon className="size-4" />
            </span>
          )}
          {/*
            Decorative on purpose. Dragging is a pointer gesture with no
            keyboard equivalent yet, and naming the grip for a screen reader
            would announce an affordance its user cannot reach. The title
            serves the pointer; when keyboard moving exists, this becomes a
            real control with a real action behind it.
          */}
          {editable && (
            <span
              data-slot="panel-grip"
              title={messages.label('label.panel.move')}
              aria-hidden="true"
              className="text-muted-foreground cursor-move"
            >
              <GripVerticalIcon className="size-4" />
            </span>
          )}
          {panel.title ?? panel.id}
        </CardTitle>
      </CardHeader>
      <CardContent className="min-h-0 flex-1 overflow-auto px-3">
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
  return panel.runtime.kind === 'record' ? (
    <RecordPanel runtime={panel.runtime as RecordViewRuntime} />
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

/**
 * The grid needs a pixel width, and the container only knows it once it is on
 * screen. `ResizeObserver` is missing in some test and server environments, so
 * the initial width stands rather than the grid failing to render at all.
 */
function useContainerWidth(initial = 1280) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState(initial);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(entries => {
      const measured = entries[0]?.contentRect.width ?? 0;
      if (measured > 0) setWidth(measured);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return { ref, width };
}
