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

import type { AnalysisView } from '../analysis/index.js';
import type { FieldOption } from '../model/index.js';
import { resultIssues, type ViewEngine } from '../runtime/index.js';
import { useAnalysisEditor, useWorkbench } from '../react/index.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisEditor } from './AnalysisEditor.js';
import { AnalysisTable } from './AnalysisTable.js';
import { FilterPanel } from './FilterPanel.js';
import { RefreshControl } from './RefreshControl.js';
import { QueryStrip } from './StatusStrip.js';
import type { ViewMessages } from './messages.js';
import { WorkbenchShell } from './WorkbenchShell.js';
import type { RenderFailureHandler } from './RenderBoundary.js';

export interface AnalysisWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which view is open, as `value` is on an input: leaving it out lets the
   * workbench own it from the effective default on, and passing it — a
   * string, or `null` for that default — puts a host's route in charge, every
   * later change opening what it names. It goes through the leave guard, so a
   * pushed view never takes an unsaved draft away without asking
   * (`WorkbenchOptions.instanceId`).
   */
  instanceId?: string | null;
  /**
   * Told which view is open whenever that changes, in the same vocabulary
   * `instanceId` is written in — `null` is the effective default — so a host
   * can put it straight into a route and get the same view back from the
   * link.
   */
  onInstanceChange?(id: string | null): void;
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  optionsFor?(remote: string): FieldOption[] | undefined;
  /**
   * The sidebar this workbench opens on. It is view state and nothing else —
   * never saved, never asked about by the leave guard — so a host sets where
   * it starts and the shell owns it from there. Left out, a column narrower
   * than `md` opens folded and a wider one opens with the list beside it.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
  /**
   * Whether this workbench offers to fill the screen. On by default, and the
   * fold itself belongs to the shell — a host only says whether the control
   * exists, because a page that is already one full-screen view of one thing
   * has nothing to gain from a second way to say so.
   */
  expandable?: boolean;
  /**
   * Told of a render failure one of the workbench's boundaries caught — the
   * host's action slots, the editor, the result, a panel. The part shows a
   * recoverable error state in place regardless; this is the host's copy.
   */
  onRenderFailure?: RenderFailureHandler;
}

/**
 * The default Analysis workbench: the view list, the conditions, what to
 * aggregate, and the result as a table or a chart.
 *
 * Which of the two is showing is part of the saved config, and both keep
 * their own settings, so switching back and forth loses nothing.
 */
export function AnalysisWorkbench({
  engine,
  definitionId,
  instanceId,
  onInstanceChange,
  theme,
  messages: wording,
  locale,
  optionsFor,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable,
  onRenderFailure,
}: AnalysisWorkbenchProps) {
  const workbench = useWorkbench(engine, definitionId, {
    kind: 'analysis',
    instanceId,
    onInstanceChange,
  });
  const { filter, runtime, state } = workbench;
  const analysis = useAnalysisEditor(runtime);

  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  // The chart the result was shaped by, not the draft being edited: until
  // Run, the draft's aliases may name other columns than the ones the
  // result's categories came from, and a category is named through its column.
  const shaped = state?.result?.config;
  const chart = shaped?.kind === 'analysis' ? shaped.chart : analysis.chart;

  return (
    <WorkbenchShell
      workbench={workbench}
      kind="analysis"
      title={engine.definitions.get(definitionId)?.title}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      defaultSidebarOpen={defaultSidebarOpen}
      onSidebarOpenChange={onSidebarOpenChange}
      expandable={expandable}
      onRenderFailure={onRenderFailure}
      // This workbench has no result toolbar to put it in — the analysis
      // result is a table or a chart, not a bar of controls — so the
      // refresh sits with the other view-level controls in the title bar.
      freshness={
        <RefreshControl refresh={workbench.refresh} variant="outline" />
      }
      // A grouping that filled its limit is a fact about this result, so it
      // is said beside the config's own findings and stays until the next
      // result replaces it. The strip sits above both layouts, which is why
      // a truncated pie is labelled as surely as a truncated table.
      warnings={[
        ...(state?.issues ?? []),
        ...resultIssues(state?.result?.data),
      ]}
      editor={
        /* Not frozen while a query runs: editing never re-queries, and a
           refresh that lands mid-edit must not take the inputs away. */
        <>
          <FilterPanel filter={filter} optionsFor={optionsFor} />
          <AnalysisEditor analysis={analysis} />
        </>
      }
      strips={
        <QueryStrip
          error={state?.query.status === 'error' ? state.query.error : null}
          stale={state?.result != null}
          onRetry={() => runtime?.refresh()}
        />
      }
      result={
        view &&
        (analysis.layout === 'chart' && view.chart ? (
          <AnalysisChart
            data={view.chart}
            spec={chart}
            columns={view.schema ?? view.columns}
          />
        ) : (
          <AnalysisTable view={view} />
        ))
      }
    />
  );
}
