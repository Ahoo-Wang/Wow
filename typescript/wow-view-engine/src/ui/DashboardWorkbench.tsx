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
import { RefreshCwIcon } from 'lucide-react';
import type { FieldOption } from '../model/index.js';
import type { ViewEngine } from '../runtime/index.js';
import {
  useDashboard,
  useFilterEditor,
  useOpenView,
  useSaveCommands,
  useViewList,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { DashboardGrid } from './DashboardGrid.js';
import { FilterPanel } from './FilterPanel.js';
import { SaveActions } from './SaveActions.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { ViewList } from './ViewList.js';
import { useViewMessages } from './MessagesProvider.js';
import { ViewSurface } from './ViewSurface.js';

export interface DashboardWorkbenchProps {
  engine: ViewEngine;
  definitionId: string;
  /** Opens this view first; the user's effective default when left out. */
  instanceId?: string | null;
  /** Whether panels may be dragged and resized. */
  editable?: boolean;
  theme?: 'light' | 'dark';
  optionsFor?(remote: string): FieldOption[] | undefined;
}

/**
 * The default Dashboard workbench: the view list, the global filter, the
 * panels and the save commands.
 *
 * The filter panel here edits the dashboard's own fields rather than a
 * definition's, which is why a runtime reports the fields to edit against
 * instead of the editor reading them off a definition. Submitting it applies
 * the dashboard, and every panel re-runs with the condition mapped onto its
 * own fields.
 */
export function DashboardWorkbench({
  engine,
  definitionId,
  instanceId = null,
  editable = false,
  theme,
  optionsFor,
}: DashboardWorkbenchProps) {
  const list = useViewList(engine, definitionId);
  const [chosen, setChosen] = useState<string | null>(instanceId);

  const opened = useOpenView(engine, chosen ?? list.defaultInstanceId);
  const runtime = opened.runtime;
  const state = useViewRuntime(runtime);
  const board = runtime?.kind === 'dashboard' ? runtime : null;
  const dashboard = useDashboard(board);
  const filter = useFilterEditor(runtime);
  const commands = useSaveCommands(engine, runtime);
  const messages = useViewMessages();

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
            <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
            <AlertDescription>{messages.issue(opened.error)}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {board && (
          <>
            {/* Without global fields there is nothing to filter, and an empty
                panel would only take up room. */}
            {dashboard.panels.length > 0 && filter.fields.length > 0 && (
              <FilterPanel filter={filter} optionsFor={optionsFor} />
            )}

            {errors.length > 0 && (
              <Alert variant="destructive">
                <AlertTitle>
                  {messages.label('label.dashboard.needs-fixing')}
                </AlertTitle>
                <AlertDescription>{messages.issues(errors)}</AlertDescription>
              </Alert>
            )}

            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={dashboard.refresh}
                disabled={dashboard.resolving}
              >
                <RefreshCwIcon />
                {messages.label('label.toolbar.refresh')}
              </Button>
              <SaveActions
                commands={commands}
                title={state?.title ?? ''}
                onSaved={saved => {
                  setChosen(saved.id);
                  list.reload();
                }}
                onRenamed={instance => {
                  // Pin the view before the reload: a workbench riding on the
                  // default would otherwise close its runtime and lose the draft.
                  setChosen(instance.id);
                  list.reload();
                }}
                onDeleted={() => {
                  // The engine let the runtime go with the instance. Reload so
                  // the list drops it and the default moves on; the open id
                  // follows the new default, or empties with the list.
                  setChosen(null);
                  list.reload();
                }}
                onRecovered={() => list.reload()}
              />
            </div>

            <DashboardGrid dashboard={dashboard} editable={editable} />
          </>
        )}
      </main>
    </ViewSurface>
  );
}
