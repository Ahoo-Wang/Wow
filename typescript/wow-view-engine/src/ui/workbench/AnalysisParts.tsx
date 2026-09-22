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

import { useEffect, useRef } from 'react';
import type { AnalysisView } from '../../analysis/index.js';
import type { AnalysisViewConfig, FieldOption } from '../../model/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import {
  useAnalysisEditor,
  type WorkbenchController,
} from '../../react/index.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisEditor } from '../AnalysisEditor.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { AnalysisEmpty } from '../analysis/EmptyResult.js';
import { useAnnouncer } from '../Announcer.js';
import { FilterPanel } from '../FilterPanel.js';
import type { ViewMessages } from '../messages.js';
import { useViewMessages } from '../MessagesProvider.js';
import { NO_PARTS, type RenderParts } from './parts.js';

export interface AnalysisPartsProps {
  workbench: WorkbenchController;
  /** The open analysis view, or null while what is open is not one. */
  runtime: ViewRuntime<AnalysisViewConfig> | null;
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  optionsFor?(remote: string): FieldOption[] | undefined;
  children: RenderParts;
}

/**
 * What makes an analysis view an analysis view: the conditions, what to
 * aggregate, and the result as a table or a chart. Which of the two is
 * showing is part of the saved config, and both keep their own settings, so
 * switching back and forth loses nothing.
 *
 * It renders nothing of its own — it hands its parts to `children`, which
 * draws the shell around them, and hands back none while no analysis view
 * is open (`parts.ts`).
 */
export function AnalysisParts({
  workbench,
  runtime,
  messages: wording,
  optionsFor,
  children,
}: AnalysisPartsProps) {
  const messages = useViewMessages(wording);
  const { filter, state } = workbench;
  const analysis = useAnalysisEditor(runtime);

  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  /**
   * The config the result was shaped by, not the draft being edited.
   *
   * Everything the result block reads comes from this one place: the layout
   * that decides between table and chart, and the chart spec whose aliases
   * name the result's columns. They used to come from two — the layout from
   * the draft and the spec from the result — so a draft switched to `chart`
   * before Run asked for a chart of a result shaped as a table, and there was
   * nothing to draw. One source cannot disagree with itself.
   */
  const applied = state?.result?.config;
  const shaped = applied?.kind === 'analysis' ? applied : undefined;
  const layout = shaped?.layout ?? analysis.layout;
  const chart = shaped?.chart ?? analysis.chart;

  // The one live region of this surface: a query that lands is a change of
  // the numbers on screen, and a reader who cannot see them has to be told
  // — as the record view's rows are (`record/queryAnnouncement.ts`).
  const { say, region: announcement } = useAnnouncer('analysis-announcement');
  const querying = state?.query.status === 'loading';
  const sentence = querying
    ? messages.label('label.status.querying')
    : view
      ? messages.label('label.status.groups', { count: view.rows.length })
      : null;
  const said = useRef<string | null>(null);
  useEffect(() => {
    if (sentence === null || sentence === said.current) return;
    said.current = sentence;
    say(sentence);
  }, [say, sentence]);

  if (!runtime) return children(NO_PARTS);
  return children({
    /* Not frozen while a query runs: editing never re-queries, and a refresh
       that lands mid-edit must not take the inputs away. */
    editor: (
      <>
        <FilterPanel filter={filter} optionsFor={optionsFor} />
        <AnalysisEditor analysis={analysis} />
      </>
    ),
    result: view && (
      <>
        {/* A grouping nothing fell into is one sentence whichever layout is
            in force; a chart of no rows is a pair of empty axes, which reads
            as a drawing that failed rather than as a range that matched
            nothing. */}
        {view.rows.length === 0 ? (
          <AnalysisEmpty />
        ) : layout === 'chart' && view.chart ? (
          <AnalysisChart
            data={view.chart}
            spec={chart}
            columns={view.schema ?? view.columns}
          />
        ) : (
          <AnalysisTable view={view} />
        )}
        {/* Last in the block, where nothing about it can be reached by a
            pointer or a tab: it draws nothing and is read, not seen. */}
        {announcement}
      </>
    ),
  });
}
