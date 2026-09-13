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

import {
  useContext,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { FilterPanel } from '../filter/FilterPanel.js';
import { cn } from '../lib/utils.js';
import type { ViewEngine } from '../engine/ViewEngine.js';
import { RecordAppliedFilters } from './page/RecordAppliedFilters.js';
import { RecordGlobalToolbar } from './page/RecordGlobalToolbar.js';
import { RecordContent } from './RecordContent.js';
import {
  useViewExpansion,
  ViewExpansionContext,
} from '../view/viewExpansion.js';
import type {
  RecordExtensions,
  RecordToolbarRenderContext,
  RecordCardRenderContext,
  RecordPaginationRenderContext,
} from './recordReactTypes.js';
export interface RecordViewProps {
  engine: ViewEngine;
  extensions?: RecordExtensions;
  filterContext?: unknown;
  selectable?: boolean;
  /** Pause periodic reads while the host performs an external business action. */
  autoRefreshPaused?: boolean;
  /** Leading content in the global toolbar, used by ViewPage for saving and instance navigation. */
  toolbarStart?: ReactNode;
  renderToolbar?(context: RecordToolbarRenderContext): ReactNode;
  renderCard?(context: RecordCardRenderContext): ReactNode;
  renderPagination?(context: RecordPaginationRenderContext): ReactNode;
  configurationOpen?: boolean;
  onConfigurationOpenChange?(open: boolean): void;
  className?: string;
}
/** Renders the selected instance. The caller owns engine.load()/dispose(). */
export function RecordView({
  engine,
  extensions,
  filterContext,
  selectable = false,
  autoRefreshPaused = false,
  className,
  toolbarStart,
  renderToolbar,
  renderCard,
  renderPagination,
  configurationOpen,
  onConfigurationOpenChange,
}: RecordViewProps) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const id = state.selectedInstanceId;
  const current = id ? state.sessions[id] : undefined;
  const session = current?.kind === 'record' ? current : undefined;
  const definition = state.definition?.record
    ? (state.definition as typeof state.definition & {
        record: NonNullable<typeof state.definition.record>;
      })
    : null;
  const [localFiltersOpen, setLocalFiltersOpen] = useState(true);
  const filtersOpen = configurationOpen ?? localFiltersOpen;
  const setFiltersOpen = onConfigurationOpenChange ?? setLocalFiltersOpen;
  const [localError, setLocalError] = useState<{
    id: string;
    message: string;
  } | null>(null);
  const editorEpoch = session?.editorEpoch;
  const filterCommands = useMemo(
    () => (id && editorEpoch !== undefined ? engine.record(id) : undefined),
    [engine, id, editorEpoch],
  );
  const rootRef = useRef<HTMLElement>(null);
  const inheritedExpansion = useContext(ViewExpansionContext);
  const localExpansion = useViewExpansion(
    rootRef,
    Boolean(session && definition && !inheritedExpansion),
  );
  const expansion = inheritedExpansion ?? localExpansion;
  function run(action: () => void | Promise<void>) {
    if (!id) return;
    setLocalError(null);
    try {
      void Promise.resolve(action()).catch(error =>
        setLocalError({
          id,
          message: error instanceof Error ? error.message : '操作失败',
        }),
      );
    } catch (error) {
      setLocalError({
        id,
        message: error instanceof Error ? error.message : '操作失败',
      });
    }
  }
  const refresh = async () => {
    if (id && engine.getSnapshot().sessions[id])
      await engine.record(id).refresh();
  };
  if (!id || !session || !definition || !filterCommands) return null;
  const querying =
    session.queryStatus === 'loading' || session.queryStatus === 'waiting';
  return (
    <section
      ref={rootRef}
      className={cn(
        'fve-root fve:flex fve:min-w-0 fve:flex-col fve:rounded-lg fve:border fve:bg-background',
        className,
      )}
      aria-label="数据视图"
      data-slot="record-view"
    >
      <FilterPanel
        key={`filter:${id}:${session.editorEpoch}`}
        value={session.filterDraft}
        fields={definition.fields}
        timeZone={definition.timeZone}
        onApply={() => engine.record(id).applyFilter()}
        appliedValue={session.filterBaseline}
        onChange={draft => filterCommands.setFilterDraft(draft)}
        onValidityChange={valid => filterCommands.setFilterValidity(valid)}
        allowedOperators={definition.allowedOperators}
        editors={definition.filterEditors}
        extensions={extensions}
        context={filterContext}
        querying={querying}
        collapsed={!filtersOpen}
        className="fve:border-t fve:p-3"
        renderToolbar={filterToolbar => (
          <RecordGlobalToolbar
            engine={engine}
            definition={definition}
            session={session}
            extensions={extensions}
            toolbarStart={toolbarStart}
            filterToolbar={filterToolbar}
            filtersOpen={filtersOpen}
            onFiltersOpenChange={setFiltersOpen}
            rootRef={rootRef}
            paused={autoRefreshPaused}
            expansion={expansion}
            refresh={refresh}
            onRefresh={() => run(refresh)}
            onLayoutChange={layout =>
              run(() => engine.record(id).setLayout(layout))
            }
          />
        )}
      />
      <RecordAppliedFilters
        engine={engine}
        definition={definition}
        session={session}
        run={run}
      />
      <RecordContent
        getSnapshot={() => {
          const latest = engine.getSnapshot().sessions[id];
          return latest?.kind === 'record' ? latest : undefined;
        }}
        session={session}
        definition={definition}
        commands={filterCommands}
        extensions={extensions}
        selectable={selectable}
        renderToolbar={renderToolbar}
        renderCard={renderCard}
        renderPagination={renderPagination}
        error={localError?.id === id ? localError.message : null}
      />
    </section>
  );
}
