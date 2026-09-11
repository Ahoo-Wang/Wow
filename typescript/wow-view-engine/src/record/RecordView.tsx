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
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { FilterPanel } from '../filter/FilterPanel.js';
import { cn } from '../lib/utils.js';
import { RecordCardList } from './RecordCardList.js';
import { RecordTable } from './RecordTable.js';
import { RecordRegion } from './RecordRegion.js';
import type { ViewEngine } from '../engine/ViewEngine.js';
import { RecordAppliedFilters } from './page/RecordAppliedFilters.js';
import { RecordGlobalToolbar } from './page/RecordGlobalToolbar.js';
import { RecordPagination } from './page/RecordPagination.js';
import { RecordToolbar } from './page/RecordToolbar.js';
import {
  bindRecordPagination,
  getRecordPaginationPolicy,
} from './page/recordPaginationPolicy.js';
import type {
  RecordPaginationRenderContext,
  RecordTableProps,
  RecordToolbarRenderContext,
  ViewExtensions,
  RecordCardRenderContext,
} from './recordReactTypes.js';
import {
  useViewExpansion,
  ViewExpansionContext,
} from '../view/viewExpansion.js';
import type {
  ViewDefinition,
  RecordViewDefinition,
} from '../contracts/viewModel.js';
import type { DeepReadonly } from '../lib/types.js';

function hasRecordCapability(
  definition: DeepReadonly<ViewDefinition> | null,
): definition is DeepReadonly<RecordViewDefinition> {
  return definition?.record !== undefined;
}

export interface RecordViewProps {
  engine: ViewEngine;
  extensions?: ViewExtensions;
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
  renderPagination,
  renderCard,
  configurationOpen,
  onConfigurationOpenChange,
}: RecordViewProps) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const [localFiltersOpen, setLocalFiltersOpen] = useState(true);
  const filtersOpen = configurationOpen ?? localFiltersOpen;
  const setFiltersOpen = onConfigurationOpenChange ?? setLocalFiltersOpen;
  const [localError, setLocalError] = useState<{
    id: string;
    message: string;
  } | null>(null);
  const id = state.selectedInstanceId;
  const current = id ? state.sessions[id] : undefined;
  const session = current?.kind === 'record' ? current : undefined;
  const recordId = session?.instance.id;
  const editorEpoch = session?.editorEpoch;
  const filterCommands = useMemo(
    () =>
      recordId && editorEpoch !== undefined
        ? engine.record(recordId)
        : undefined,
    [engine, recordId, editorEpoch],
  );
  const selectionCount = session?.selectedRowKeys.length ?? 0;
  const {
    id: instanceId,
    definitionId,
    title,
    scope,
    revision,
  } = session?.instance ?? {};
  const queryConfig = session?.result?.config ?? session?.instance.config;
  const presentation = session?.instance.config.presentation;
  // Keep live metadata without invalidating rendered records for query-only drafts.
  const resultInstance = useMemo(
    () =>
      instanceId !== undefined && queryConfig && presentation
        ? {
            id: instanceId,
            definitionId: definitionId!,
            title: title!,
            scope: scope!,
            revision: revision!,
            kind: 'record' as const,
            config: { ...queryConfig, presentation },
          }
        : undefined,
    [
      instanceId,
      definitionId,
      title,
      scope,
      revision,
      queryConfig,
      presentation,
    ],
  );
  useEffect(() => {
    if (!selectable && id && selectionCount)
      engine.record(id!).setSelection([]);
  }, [engine, id, selectable, selectionCount]);
  const definition = hasRecordCapability(state.definition)
    ? state.definition
    : null;
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
      await engine.record(id!).refresh();
  };
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
    onQueryRetry: () => run(() => engine.record(id!).retryQuery()),
    onSummaryRetry: () => {
      void engine
        .record(id!)
        .refreshSummary()
        .catch(() => {});
    },
    onSelectionChange: keys => run(() => engine.record(id!).setSelection(keys)),
    onColumnsChange: columns =>
      run(() => engine.record(id!).setColumns(columns)),
    onSortChange: sort => run(() => engine.record(id!).setSort(sort)),
  };
  if (!id || !session || !definition) return null;
  const { instance } = session;
  const querying = session.queryStatus === 'loading';
  const paginationPolicy = getRecordPaginationPolicy(session);
  const paginationOperations = bindRecordPagination(engine, id);
  const tableOperations = {
    setLayout(
      layout: Parameters<ReturnType<ViewEngine['record']>['setLayout']>[0],
    ) {
      if (engine.getSnapshot().sessions[id])
        engine.record(id!).setLayout(layout);
    },
    setCardConfig(
      card: Parameters<ReturnType<ViewEngine['record']>['setCardConfig']>[0],
    ) {
      if (engine.getSnapshot().sessions[id])
        engine.record(id!).setCardConfig(card);
    },
    clearSelection() {
      if (engine.getSnapshot().sessions[id])
        engine.record(id!).setSelection([]);
    },
    setColumns(
      columns: Parameters<ReturnType<ViewEngine['record']>['setColumns']>[0],
    ) {
      if (engine.getSnapshot().sessions[id])
        engine.record(id!).setColumns(columns);
    },
    refresh,
  };
  const Result =
    instance.config.presentation.layout === 'table'
      ? RecordTable
      : RecordCardList;
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
      data-slot="record-view"
    >
      <FilterPanel
        key={`filter:${id}:${session.editorEpoch}`}
        value={session.filterDraft}
        fields={definition.fields}
        timeZone={definition.timeZone}
        onApply={() => engine.record(id!).applyFilter()}
        appliedValue={session.filterBaseline}
        onChange={draft => filterCommands!.setFilterDraft(draft)}
        onValidityChange={valid => filterCommands!.setFilterValidity(valid)}
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
              run(() => tableOperations.setLayout(layout))
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
      <RecordRegion
        key={`toolbar-region:${id}`}
        label="记录工具栏"
        render={renderToolbar}
        resetKey={[renderToolbar, definition, session]}
        context={{
          definition,
          session,
          defaultContent: (
            <RecordToolbar
              key={`toolbar:${id}`}
              definition={definition}
              session={session}
              extensions={extensions}
              selectable={selectable}
              refresh={refresh}
              onSelectionClear={() => run(tableOperations.clearSelection)}
              onColumnsChange={tableHandlers.onColumnsChange}
              onSortChange={tableHandlers.onSortChange}
              onCardChange={
                renderCard ? undefined : tableOperations.setCardConfig
              }
            />
          ),
          appliedFilter: session.appliedFilter,
          querying,
          selectedRowKeys: session.selectedRowKeys,
          ...tableOperations,
        }}
      />
      {error && (
        <div
          role="alert"
          className="fve:mx-3 fve:mb-3 fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:border-destructive/30 fve:p-3 fve:text-sm fve:text-destructive"
        >
          <span>{error}</span>
        </div>
      )}
      <Result
        renderCard={renderCard}
        key={`${instance.config.presentation.layout}:${id}`}
        className="fve:rounded-none fve:border-x-0 fve:border-b-0"
        definition={definition}
        instance={resultInstance!}
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
      <RecordRegion
        key={`pagination-region:${id}`}
        label="分页"
        render={renderPagination}
        resetKey={[renderPagination, definition, session]}
        context={{
          definition,
          session,
          defaultContent: (
            <RecordPagination
              session={session}
              policy={paginationPolicy}
              operations={paginationOperations}
              run={run}
            />
          ),
          ...paginationPolicy,
          ...paginationOperations,
        }}
      />
    </section>
  );
}
