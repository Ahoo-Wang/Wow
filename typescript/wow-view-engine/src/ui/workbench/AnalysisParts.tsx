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
import {
  drillConditions,
  focusOn,
  groupFor,
  splitBy,
  type AnalysisView,
} from '../../analysis/index.js';
import { describeFilter } from '../../filter/index.js';
import type { AnalysisViewConfig, FieldOption } from '../../model/index.js';
import type { ViewRuntime } from '../../runtime/index.js';
import {
  useAnalysisEditor,
  type WorkbenchController,
} from '../../react/index.js';
import { AnalysisChart } from '../AnalysisChart.js';
import { AnalysisEditor } from '../AnalysisEditor.js';
import { AnalysisTable } from '../AnalysisTable.js';
import { DrillMenu, type Pick } from '../analysis/DrillMenu.js';
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

  // The group the user pressed, on the chart or in the table, and the menu
  // over it (D20 追问). Its conditions come from the config the result was
  // shaped by — the row is a row of that result — and are described by the
  // origin's fields, in the applied bar's words.
  const [pick, setPick] = useState<Pick | null>(null);
  const conditions =
    pick && shaped && runtime
      ? drillConditions(shaped, runtime.fields, runtime.kinds, pick.row, {
          timeZone: runtime.environment.timeZone,
        })
      : null;
  const described =
    conditions && runtime
      ? describeFilter(
          runtime.fields,
          { op: 'and', children: conditions },
          runtime.kinds,
        )
      : [];
  // Only a group a condition can say is worth a menu: an analysis over
  // expanded elements has rows no root condition selects, so its marks and
  // rows are not pressable at all.
  const pickable =
    shaped !== undefined &&
    !(shaped.elements && shaped.elements.length > 0) &&
    runtime !== null;
  const onPick = pickable
    ? (row: Pick['row'], anchor: Pick['anchor']) => setPick({ row, anchor })
    : undefined;
  // The dimensions the group can be split by: groupable fields the result
  // on screen is not already grouped by. Read off the config that shaped it
  // rather than off the draft, for the same reason the conditions are: the
  // group pressed is a group of that result, not of what is being edited.
  const grouped = new Set((shaped?.groups ?? []).map(group => group.field));
  const splits = analysis.fields
    .filter(option => option.groups.length > 0 && !grouped.has(option.field))
    .map(option => ({ field: option.field, label: option.label }));
  const close = () => setPick(null);
  const records = () => {
    if (conditions) workbench.drill(conditions);
    close();
  };
  const focus = () => {
    if (conditions && shaped && runtime) {
      runtime.edit(focusOn(shaped, conditions));
      runtime.apply();
    }
    close();
  };
  const split = (name: string) => {
    const field = runtime?.fields.find(entry => entry.name === name);
    const option = analysis.fields.find(entry => entry.field === name);
    if (conditions && shaped && runtime && field && option) {
      runtime.edit(
        splitBy(
          shaped,
          conditions,
          groupFor(field, option, runtime.kinds.get(field.kind)),
        ),
      );
      runtime.apply();
    }
    close();
  };

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
            onPick={onPick}
          />
        ) : (
          <AnalysisTable view={view} onPick={onPick} />
        )}
        {pickable && (
          <DrillMenu
            pick={conditions ? pick : null}
            onClose={close}
            conditions={described}
            canDrill={workbench.canDrill}
            splits={splits}
            onRecords={records}
            onSplit={split}
            onFocus={focus}
          />
        )}
        {/* Last in the block, where nothing about it can be reached by a
            pointer or a tab: it draws nothing and is read, not seen. */}
        {announcement}
      </>
    ),
  });
}
