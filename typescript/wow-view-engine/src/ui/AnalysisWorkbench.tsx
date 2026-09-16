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

import { useState } from 'react';
import type { AnalysisView } from '../analysis/index.js';
import type { FieldOption } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import {
  useAnalysisEditor,
  useFilterEditor,
  useOpenView,
  useSaveCommands,
  useViewList,
  useViewRuntime,
} from '../react/index.js';
import { AnalysisChart } from './AnalysisChart.js';
import { AnalysisEditor } from './AnalysisEditor.js';
import { AnalysisTable } from './AnalysisTable.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { FilterPanel } from './FilterPanel.js';
import { SaveActions } from './SaveActions.js';
import { ViewList } from './ViewList.js';
import { ViewSurface } from './ViewSurface.js';

export interface AnalysisWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  instanceId?: string | null;
  theme?: 'light' | 'dark';
  optionsFor?(remote: string): FieldOption[] | undefined;
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
  instanceId = null,
  theme,
  optionsFor,
}: AnalysisWorkbenchProps) {
  const list = useViewList(engine, definitionId);
  const [chosen, setChosen] = useState<string | null>(instanceId);

  const opened = useOpenView(engine, chosen ?? list.defaultInstanceId);
  const runtime = opened.runtime;
  const state = useViewRuntime(runtime);
  const analysis = useAnalysisEditor(runtime);
  const filter = useFilterEditor(runtime);
  const commands = useSaveCommands(engine, runtime);

  const data = state?.result?.data;
  const view: AnalysisView | null =
    data?.kind === 'analysis' ? data.view : null;
  const loading = state?.query.status === 'loading';
  const errors = (state?.issues ?? []).filter(
    found => found.severity === 'error',
  );

  return (
    <ViewSurface theme={theme} className="gap-0 md:flex-row">
      <aside className="flex w-56 shrink-0 flex-col gap-2 p-3">
        <ViewList
          list={list}
          currentId={state?.saved?.id ?? null}
          onOpen={setChosen}
        />
      </aside>

      <Separator orientation="vertical" className="hidden md:block" />

      <main className="flex min-w-0 flex-1 flex-col gap-3 p-3">
        {opened.error && (
          <Alert variant="destructive">
            <AlertTitle>This view could not be opened</AlertTitle>
            <AlertDescription>{opened.error.code}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {runtime && (
          <>
            <FilterPanel
              filter={filter}
              optionsFor={optionsFor}
              disabled={loading}
            />
            <AnalysisEditor analysis={analysis} disabled={loading} />

            {errors.length > 0 && (
              <Alert variant="destructive">
                <AlertTitle>This view needs fixing before it runs</AlertTitle>
                <AlertDescription>
                  {errors.map(found => found.code).join(', ')}
                </AlertDescription>
              </Alert>
            )}

            <SaveActions
              commands={commands}
              title={state?.title ?? ''}
              onSaved={saved => setChosen(saved.id)}
              onDeleted={() => setChosen(null)}
            />

            {state?.query.status === 'error' && state.query.error && (
              <Alert variant="destructive">
                <AlertTitle>The query failed</AlertTitle>
                <AlertDescription>{state.query.error.code}</AlertDescription>
              </Alert>
            )}

            {view &&
              (analysis.layout === 'chart' && view.chart ? (
                <AnalysisChart data={view.chart} spec={analysis.chart} />
              ) : (
                <AnalysisTable view={view} />
              ))}
          </>
        )}
      </main>
    </ViewSurface>
  );
}
