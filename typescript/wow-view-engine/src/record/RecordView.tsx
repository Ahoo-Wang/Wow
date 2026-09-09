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
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { FilterPanel } from '../filter/FilterPanel.js';
import { cn } from '../lib/utils.js';
import { RecordTable } from './RecordTable.js';
import type { ViewEngine } from './ViewEngine.js';
import { RecordAppliedFilters } from './page/RecordAppliedFilters.js';
import { RecordGlobalToolbar } from './page/RecordGlobalToolbar.js';
import { RecordPagination } from './page/RecordPagination.js';
import { RecordTableToolbar } from './page/RecordTableToolbar.js';
import type { RecordTableProps, ViewExtensions } from './recordReactTypes.js';
import { useViewExpansion, ViewExpansionContext } from './viewExpansion.js';

export interface RecordViewProps {
  engine: ViewEngine;
  extensions?: ViewExtensions;
  filterContext?: unknown;
  selectable?: boolean;
  /** Pause periodic reads while the host performs an external business action. */
  autoRefreshPaused?: boolean;
  /** Leading content in the global toolbar, used by ViewPage for saving and instance navigation. */
  toolbarStart?: ReactNode;
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
}: RecordViewProps) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const [filtersOpen, setFiltersOpen] = useState(true);
  const [localError, setLocalError] = useState<{
    id: string;
    message: string;
  } | null>(null);
  const id = state.selectedInstanceId;
  const session = id ? state.sessions[id] : undefined;
  const selectionCount = session?.selectedRowKeys.length ?? 0;
  useEffect(() => {
    if (!selectable && id && selectionCount) engine.setSelection([], id);
  }, [engine, id, selectable, selectionCount]);
  const definition = state.definition;
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
  const refresh = () => engine.refresh(id ?? undefined);
  const tableHandlers: Required<
    Pick<
      RecordTableProps,
      | 'onQueryRetry'
      | 'onSummaryRetry'
      | 'onSelectionChange'
      | 'onColumnsChange'
      | 'onSortChange'
    >
  > = {
    onQueryRetry: () => run(() => engine.retryQuery(id ?? undefined)),
    onSummaryRetry: () => {
      void engine.refreshSummary(id ?? undefined).catch(() => {});
    },
    onSelectionChange: keys =>
      run(() => engine.setSelection(keys, id ?? undefined)),
    onColumnsChange: columns =>
      run(() => engine.setColumns(columns, id ?? undefined)),
    onSortChange: sort => run(() => engine.setSort(sort, id ?? undefined)),
  };
  if (!id || !session || !definition) return null;
  const { instance } = session;
  const querying = session.queryStatus === 'loading';
  const error =
    !session.queryError && localError?.id === id ? localError.message : null;
  return (
    <section
      ref={rootRef}
      className={cn(
        'fve-root fve:flex fve:min-w-0 fve:flex-col fve:rounded-lg fve:border fve:bg-background',
        className,
      )}
      aria-label="数据视图"
    >
      <FilterPanel
        key={`filter:${id}`}
        value={session.filterDraft}
        fields={definition.fields}
        timeZone={definition.timeZone}
        onApply={() => engine.applyFilter(id)}
        appliedValue={session.filterBaseline}
        onChange={draft => engine.setFilterDraft(draft, id)}
        onValidityChange={valid => engine.setFilterValidity(valid, id)}
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
          />
        )}
      />
      <RecordAppliedFilters
        engine={engine}
        definition={definition}
        session={session}
        run={run}
      />
      <RecordTableToolbar
        key={`toolbar:${id}`}
        definition={definition}
        session={session}
        extensions={extensions}
        selectable={selectable}
        refresh={refresh}
        onSelectionClear={() => run(() => engine.setSelection([], id))}
        onColumnsChange={tableHandlers.onColumnsChange}
      />
      {error && (
        <div
          role="alert"
          className="fve:mx-3 fve:mb-3 fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:border-destructive/30 fve:p-3 fve:text-sm fve:text-destructive"
        >
          <span>{error}</span>
        </div>
      )}
      <RecordTable
        key={`table:${id}`}
        className="fve:rounded-none fve:border-x-0 fve:border-b-0"
        definition={definition}
        instance={instance}
        appliedFilter={session.appliedFilter}
        rows={session.rows}
        queryError={session.queryError}
        {...tableHandlers}
        pageSummary={session.pageSummary}
        allSummary={session.allSummary}
        extensions={extensions}
        querying={querying}
        selectable={selectable}
        selectedRowKeys={session.selectedRowKeys}
        refresh={refresh}
      />
      <RecordPagination engine={engine} session={session} run={run} />
    </section>
  );
}
