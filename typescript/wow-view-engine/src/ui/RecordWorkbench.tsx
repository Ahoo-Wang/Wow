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
import type { FieldOption } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import {
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewList,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { FilterPanel } from './FilterPanel.js';
import { RecordCards } from './RecordCards.js';
import { RecordTable } from './RecordTable.js';
import { RecordToolbar } from './RecordToolbar.js';
import { SaveActions } from './SaveActions.js';
import { ViewSurface } from './ViewSurface.js';
import { ViewList } from './ViewList.js';

export interface RecordWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /** Opens this view first; the user's effective default when left out. */
  instanceId?: string | null;
  theme?: 'light' | 'dark';
  optionsFor?(remote: string): FieldOption[] | undefined;
}

/**
 * The default Record workbench: the view list, the conditions, the result and
 * the save commands.
 *
 * It is one composition of the controllers in `/react`, not a privileged one.
 * An application that wants different markup builds its own from the same
 * hooks and loses nothing.
 */
export function RecordWorkbench({
  engine,
  definitionId,
  instanceId = null,
  theme,
  optionsFor,
}: RecordWorkbenchProps) {
  const list = useViewList(engine, definitionId);
  const [chosen, setChosen] = useState<string | null>(instanceId);
  const openId = chosen ?? list.defaultInstanceId;

  const opened = useOpenView(engine, openId);
  const runtime = opened.runtime;
  const state = useViewRuntime(runtime);
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record);
  const filter = useFilterEditor(runtime);
  const commands = useSaveCommands(engine, runtime);

  const fields = runtime?.definition.fields ?? [];
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
              disabled={table.loading}
            />

            {errors.length > 0 && (
              <Alert variant="destructive">
                <AlertTitle>This view needs fixing before it runs</AlertTitle>
                <AlertDescription>
                  {errors.map(found => found.code).join(', ')}
                </AlertDescription>
              </Alert>
            )}

            <RecordToolbar table={table} fields={fields}>
              <SaveActions
                commands={commands}
                title={state?.title ?? ''}
                onSaved={saved => setChosen(saved.id)}
                onDeleted={() => setChosen(null)}
              />
            </RecordToolbar>

            {table.error && (
              <Alert variant="destructive">
                <AlertTitle>The query failed</AlertTitle>
                <AlertDescription>{table.error.code}</AlertDescription>
              </Alert>
            )}

            {table.layout === 'card' ? (
              <RecordCards table={table} />
            ) : (
              <RecordTable table={table} />
            )}
          </>
        )}
      </main>
    </ViewSurface>
  );
}
