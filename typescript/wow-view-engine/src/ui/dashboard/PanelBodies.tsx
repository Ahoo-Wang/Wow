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
 * What a data panel draws: a record view's rows, an analysis's table or
 * chart — the one the workbench draws, looked at as the panel's own look
 * says (D22 D) — and what a panel says when its query failed, or when its
 * look is its own.
 */

import { useState } from 'react';
import { UnplugIcon } from 'lucide-react';
import {
  PANEL_PRESENTATION_MEMBERS,
  type AnalysisViewConfig,
  type DataViewConfig,
  type Issue,
  type RecordData,
} from '../../model/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  usePanelFollowUps,
  useRecordTable,
  useViewRuntime,
  type DashboardPanelView,
} from '../../react/index.js';
import type { RecordViewRuntime, ViewRuntime } from '../../runtime/index.js';
import {
  DrillMenu,
  groupText,
  type Pick as Pressed,
} from '../analysis/DrillMenu.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import {
  boardContext,
  crossFilterSaid,
  pressMode,
  type PanelPress,
} from './press.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { RecordTable } from '../RecordTable.js';
import { QueryStrip } from '../StatusStrip.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { Button } from '../components/button.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { Skeleton } from '../components/skeleton.js';

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
export function RecordPanel({
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
  if (table.loading && table.rows.length === 0) return <PanelLoading />;
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

/**
 * The chart or the table an analysis panel shows; stale as a record panel
 * is. A press on one of its groups does what the panel's click says
 * (`press`, D22 H, I): the analysis view's own follow-up menu, each item
 * opening in the workbench through the host's route; the board's filter set
 * to the group, this panel marking it rather than narrowing; or a custom
 * destination. Without a press nothing on it is pressable.
 */
/** A panel without a press takes nothing off the board. */
const nothingHanded = () => null;

export function AnalysisPanel({
  runtime,
  onRetry,
  press,
}: {
  runtime: ViewRuntime;
  onRetry?: () => void;
  press?: PanelPress;
}) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const child = runtime as ViewRuntime<DataViewConfig>;
  // The workbench the follow-ups drive is the host's, reached by its route.
  const followUps = usePanelFollowUps(
    child,
    press?.navigate,
    press?.handOver ?? nothingHanded,
  );
  // Drawn as the workbench draws it (`useAnalysisResult`): the rows that
  // ran, looked at as the child's draft says. A panel's own look is laid
  // onto that draft without a run (D20: presentation never asks the
  // source), so a change of chart redraws the rows on hand.
  const result = useAnalysisResult(
    runtime as ViewRuntime<AnalysisViewConfig>,
    analysis,
    followUps,
  );
  const { view, chart, chartData } = result;
  const failed = state?.query.status === 'error';
  // Which group was pressed, and where, while the menu over it is open.
  const [pick, setPick] = useState<Pressed | null>(null);
  const mode = result.pickable ? pressMode(press) : null;
  const onPick =
    mode === null || !press
      ? undefined
      : (row: RecordData, anchor: Pressed['anchor'], origin?: HTMLElement) => {
          if (mode === 'menu') {
            setPick({ row, anchor, ...(origin ? { origin } : {}) });
            return;
          }
          if (mode === 'filter') {
            // The group as the follow-up menu heads it: 「仓库 是 华南」.
            const group = (result.followUp(row)?.groups ?? [])
              .map(entry => groupText(entry, messages, display))
              .join(' · ');
            const said = crossFilterSaid(
              press.crossFilter(row),
              messages,
              group,
            );
            if (said) press.say(said);
            return;
          }
          void press.destination(row).then(where => {
            if (!where) return;
            if ('to' in where) press.navigate?.(where.to);
            else if ('refused' in where)
              press.say(messages.issue(where.refused));
            else {
              // A click gone stale (D23 Q17): said, and the follow-up menu
              // opens on the group pressed instead — batch D's rule.
              press.say(messages.issue(where.fallback));
              setPick({ row, anchor, ...(origin ? { origin } : {}) });
            }
          });
        };
  // The group a press set the board's filter to is marked, not narrowed to.
  const highlight = mode === 'filter' ? press?.pressed : undefined;
  const followUp = pick ? result.followUp(pick.row) : null;

  if (failed && !view)
    return <PanelFailed error={state.query.error} onRetry={onRetry} />;
  if (!view) return <PanelLoading />;
  const body =
    analysis.layout === 'chart' && chartData ? (
      <AnalysisChart
        data={chartData}
        spec={chart}
        columns={view.schema ?? view.columns}
        // Under the stale line the chart takes what is left of the panel.
        className={failed ? 'min-h-0 flex-1' : 'h-full'}
        cutShort={view.truncated || view.atLimit !== undefined}
        onPick={onPick}
        highlight={highlight}
        menuOpen={followUp !== null && pick !== null}
      />
    ) : (
      <AnalysisTable
        view={view}
        onPick={onPick}
        opensMenu={mode === 'menu'}
        highlight={highlight}
      />
    );
  // A destination opens the menu too, on a press whose click fell back.
  const menu = (mode === 'menu' || mode === 'go') && (
    <DrillMenu
      pick={followUp ? pick : null}
      onClose={() => setPick(null)}
      followUp={followUp}
      context={boardContext(child, messages, display)}
      away
    />
  );
  if (!failed)
    return (
      <>
        {body}
        {menu}
      </>
    );
  return (
    <div className="flex h-full flex-col gap-2">
      <QueryStrip error={state.query.error} stale onRetry={onRetry} />
      {body}
      {menu}
    </div>
  );
}

/**
 * What a panel whose look is its own says about it (D22 D): 「此处改为饼图」
 * — the type it is drawn as — or, where only the totals row was changed,
 * that it looks different here. Nothing for a panel that looks as its view
 * does, or whose override no longer fit and was dropped: the warning on the
 * panel says that.
 */
export function presentationMark(
  panel: DashboardPanelView,
  messages: MessageFormatters,
): string | null {
  if (panel.panel.kind !== 'view') return null;
  const look: unknown = panel.panel.presentation;
  if (typeof look !== 'object' || look === null) return null;
  const set = Object.keys(look).filter(key =>
    (PANEL_PRESENTATION_MEMBERS as readonly string[]).includes(key),
  );
  if (set.length === 0) return null;
  if (
    panel.issues.some(
      found => found.code === 'dashboard.panel.presentation-dropped',
    )
  )
    return null;
  const draft = panel.runtime?.getSnapshot().draft;
  if (!set.includes('layout') && !set.includes('chart'))
    return messages.label('label.panel.presentation.changed');
  if (draft?.kind !== 'analysis')
    return messages.label('label.panel.presentation.changed');
  return messages.label('label.panel.presentation.as', {
    type: messages.label(
      draft.layout === 'table'
        ? 'label.layout.table'
        : `label.chart.type.${draft.chart.type}`,
    ),
  });
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

/**
 * A panel whose first answer is on its way (U-13): the skeleton for the
 * eye, and for a reader who reaches the panel's body a word saying so and
 * the body marked busy. Not a live region: a board opens a dozen panels at
 * once, and a dozen voices saying 「加载中」 is the noise the board's one
 * region is there to prevent.
 */
function PanelLoading() {
  const messages = useViewMessages();
  return (
    <div data-slot="panel-loading" aria-busy="true">
      <span className="sr-only">{messages.label('label.status.loading')}</span>
      <Skeleton aria-hidden="true" className="h-24 w-full" />
    </div>
  );
}
