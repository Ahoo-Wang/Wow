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

import { useCallback, useState, type ReactNode } from 'react';
import type {
  AnalysisSort,
  AnalysisViewConfig,
  DataViewConfig,
} from '../../model/index.js';
import {
  hasAsked,
  type ViewNavigation,
  type ViewRuntime,
} from '../../runtime/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  useFilterEditor,
  usePanelFollowUps,
  useViewRuntime,
} from '../../react/index.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { AnalysisToolbar } from '../analysis/AnalysisToolbar.js';
import { DrillMenu, type Pick as Pressed } from '../analysis/DrillMenu.js';
import { AnalysisEmpty } from '../analysis/EmptyResult.js';
import { useHeaderSort } from '../analysis/headerSort.js';
import { AppliedBar } from '../AppliedBar.js';
import { Skeleton } from '../components/skeleton.js';
import { QueryStrip } from '../StatusStrip.js';

const NO_SORT: readonly AnalysisSort[] = [];

/**
 * An analysis view on a business page (D22): its chart or its table as the
 * author saved it, and what it was asked under. In the interactive tier the
 * reader may also switch between table and chart, order the table by a
 * header, and press a group for the analysis view's own follow-up menu —
 * each item opened through the host's route as a view nobody saved, under
 * the page's narrowing as its scope (`usePanelFollowUps`, the same route a
 * dashboard panel's menu takes). Nothing of it is saved.
 */
export function EmbeddedAnalysis({
  runtime,
  interactive,
  onNavigate,
  head,
  notices,
}: {
  runtime: ViewRuntime<DataViewConfig>;
  interactive: boolean;
  onNavigate?: ((to: ViewNavigation) => void) | undefined;
  head(actions: ReactNode): ReactNode;
  notices: ReactNode;
}) {
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const filter = useFilterEditor(runtime);
  // The follow-ups exist in the interactive tier, and only with a route to
  // open them by: a menu whose items go nowhere is not offered.
  // What they open runs under the page's narrowing as this view does:
  // locked there too, the reader's own conditions none (D26 Q30).
  const pageScope = useCallback(
    () => ({ scopeFilter: runtime.scopeFilter, filter: null }),
    [runtime],
  );
  const followUps = usePanelFollowUps(
    runtime,
    interactive ? onNavigate : undefined,
    pageScope,
  );
  const result = useAnalysisResult(
    runtime as ViewRuntime<AnalysisViewConfig>,
    analysis,
    followUps,
  );
  const { view, chart, chartData } = result;
  const headerSort = useHeaderSort(analysis, result.ran?.sort ?? NO_SORT);
  // A sort needs a dimension: an ungrouped aggregation is one row anyway.
  const sortable = interactive && (result.ran?.groups.length ?? 0) > 0;
  const [pick, setPick] = useState<Pressed | null>(null);
  const followUp = pick ? result.followUp(pick.row) : null;
  // A group is pressable in the interactive tier, with a route for what the
  // menu opens: a menu whose every item goes nowhere is not one to offer.
  const pressable = interactive && onNavigate !== undefined && result.pickable;
  const onPick = pressable
    ? (row: Pressed['row'], anchor: Pressed['anchor'], origin?: HTMLElement) =>
        setPick({ row, anchor, ...(origin ? { origin } : {}) })
    : undefined;
  const failed = state?.query.status === 'error';
  const error = failed ? state.query.error : null;
  const retry = interactive ? () => runtime.refresh() : undefined;

  let body: ReactNode;
  // As in the record embed: the chart stays while the strip reports the
  // refresh that failed over it.
  if (failed && !view)
    body = <QueryStrip error={error} stale={false} onRetry={retry} />;
  else if (!view) body = <Skeleton className="h-24 w-full" />;
  else
    body = (
      <>
        <QueryStrip error={error} stale onRetry={retry} />
        {/* The layout switch is the reader's in the interactive tier; the
            static one shows what the author saved. */}
        {interactive && (
          <AnalysisToolbar analysis={analysis} columns={result.columns} />
        )}
        <div data-slot="analysis-result" className="flex min-w-0 flex-col">
          {view.rows.length === 0 ? (
            <AnalysisEmpty />
          ) : analysis.layout === 'chart' && chartData ? (
            <AnalysisChart
              data={chartData}
              spec={chart}
              columns={view.schema ?? view.columns}
              cutShort={view.truncated || view.atLimit !== undefined}
              onPick={onPick}
              menuOpen={followUp !== null && pick !== null}
              // The reader's own chart in the interactive tier; static,
              // the wheel is the host page's.
              zoomGestures={interactive}
            />
          ) : (
            <AnalysisTable
              view={view}
              onPick={onPick}
              {...(sortable ? { sorting: headerSort } : {})}
            />
          )}
        </div>
        {pressable && (
          <DrillMenu
            pick={followUp ? pick : null}
            onClose={() => setPick(null)}
            followUp={followUp}
            away
          />
        )}
      </>
    );

  return (
    <>
      {head(null)}
      {notices}
      <AppliedBar filter={filter} asked={hasAsked(state)} readOnly />
      {body}
    </>
  );
}
