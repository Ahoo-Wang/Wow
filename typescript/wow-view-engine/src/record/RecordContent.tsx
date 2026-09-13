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
  useMemo,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type {
  RecordSession,
  RecordViewDefinition,
} from '../contracts/viewModel.js';
import { sameFilterQuery } from '../filter/filterTree.js';
import { RecordActionGuard } from './RecordActionGuard.js';
import { RecordTable } from './RecordTable.js';
import { RecordCardList } from './RecordCardList.js';
import { RecordRegion } from './RecordRegion.js';
import { RecordToolbar } from './page/RecordToolbar.js';
import { RecordPagination } from './page/RecordPagination.js';
import {
  getRecordPaginationPolicy,
  bindRecordPaginationCommands,
} from './page/recordPaginationPolicy.js';
import type {
  RecordExtensions,
  RecordTableProps,
  RecordToolbarRenderContext,
  RecordCardRenderContext,
  RecordPaginationRenderContext,
} from './recordReactTypes.js';

export interface RecordContentProps {
  session: RecordSession;
  definition: RecordViewDefinition;
  commands: ReturnType<ViewEngine['record']>;
  getSnapshot?(): RecordSession | undefined;
  extensions?: RecordExtensions;
  selectable?: boolean;
  configurable?: boolean;
  paginationLabel?: string;
  error?: string | null;
  renderToolbar?(context: RecordToolbarRenderContext): ReactNode;
  renderCard?(context: RecordCardRenderContext): ReactNode;
  renderPagination?(context: RecordPaginationRenderContext): ReactNode;
}
/** Shared record result, business actions and pagination; no page/navigation dependency. */
export function RecordContent({
  session,
  definition,
  commands,
  extensions,
  selectable = false,
  configurable = true,
  paginationLabel,
  error: externalError,
  getSnapshot,
  renderToolbar,
  renderCard,
  renderPagination,
}: RecordContentProps) {
  const id = session.positionId;
  const actionToken = useMemo(
    () => ({
      id,
      appliedFilter: session.appliedFilter,
      result: session.result,
    }),
    [id, session.appliedFilter, session.result],
  );
  const selectionToken = useMemo(
    () => ({ actionToken, selectedRowKeys: session.selectedRowKeys }),
    [actionToken, session.selectedRowKeys],
  );
  const committed = useRef<{
    session: RecordSession;
    getSnapshot?: RecordContentProps['getSnapshot'];
    actionToken: object;
    selectionToken: object;
  } | null>({ session, getSnapshot, actionToken, selectionToken });
  useLayoutEffect(() => {
    committed.current = { session, getSnapshot, actionToken, selectionToken };
    return () => {
      committed.current = null;
    };
  }, [session, getSnapshot, actionToken, selectionToken]);
  const readCurrent = () => {
    const latest = committed.current;
    return latest?.getSnapshot ? latest.getSnapshot() : latest?.session;
  };
  const { instance } = session;
  const [localError, setLocalError] = useState<string | null>(null);
  const displayedFilter = session.result?.filter ?? session.appliedFilter;
  const scopeCurrent = () => {
    const latest = readCurrent();
    return (
      committed.current?.actionToken === actionToken &&
      !!latest &&
      latest.positionId === id &&
      latest.appliedFilter === session.appliedFilter &&
      sameFilterQuery(latest.appliedFilter, displayedFilter)
    );
  };
  const actionsCurrent = () => {
    const latest = readCurrent();
    return scopeCurrent() && latest?.result === session.result;
  };
  const selectionCurrent = () =>
    committed.current?.selectionToken === selectionToken &&
    actionsCurrent() &&
    readCurrent()?.selectedRowKeys === session.selectedRowKeys;
  const stale = !sameFilterQuery(displayedFilter, session.appliedFilter);
  const querying =
    session.queryStatus === 'loading' || session.queryStatus === 'waiting';
  useEffect(() => {
    if (!selectable && session.selectedRowKeys.length)
      commands.setSelection([]);
  }, [selectable, session.selectedRowKeys.length, commands]);
  function run(action: () => void | Promise<void>) {
    setLocalError(null);
    try {
      void Promise.resolve(action()).catch(error =>
        setLocalError(error instanceof Error ? error.message : '操作失败'),
      );
    } catch (error) {
      setLocalError(error instanceof Error ? error.message : '操作失败');
    }
  }
  // View commands remain bound to their instance; business writes use the committed guards above.
  const current = () => !getSnapshot || getSnapshot()?.positionId === id;
  const refresh = async () => {
    if (current()) await commands.refresh();
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
    onQueryRetry: () => run(() => commands.retryQuery()),
    onSummaryRetry: () => run(() => commands.refreshSummary()),
    onSelectionChange: keys => run(() => commands.setSelection(keys)),
    onColumnsChange: columns => {
      if (configurable) run(() => commands.setColumns(columns));
    },
    onSortChange: sort => run(() => commands.setSort(sort)),
  };
  const paginationPolicy = getRecordPaginationPolicy(session);
  const paginationCommands = () =>
    bindRecordPaginationCommands(
      getSnapshot ??
        (() => {
          const latest = readCurrent();
          return latest?.positionId === id ? latest : undefined;
        }),
      () => commands,
    );
  const paginationOperations = {
    setPage: (index: number) => paginationCommands().setPage(index),
    setPageSize: (size: number) => paginationCommands().setPageSize(size),
    nextPage: () => paginationCommands().nextPage(),
    previousPage: () => paginationCommands().previousPage(),
  };
  const tableOperations = {
    setLayout: (layout: Parameters<typeof commands.setLayout>[0]) => {
      if (configurable && current()) commands.setLayout(layout);
    },
    setCardConfig: (card: Parameters<typeof commands.setCardConfig>[0]) => {
      if (configurable && current()) commands.setCardConfig(card);
    },
    clearSelection: () => {
      if (current()) commands.setSelection([]);
    },
    setColumns: (columns: Parameters<typeof commands.setColumns>[0]) => {
      if (configurable && current()) commands.setColumns(columns);
    },
    refresh,
  };
  const Result =
    instance.config.presentation.layout === 'table'
      ? RecordTable
      : RecordCardList;
  const error = !session.queryError ? (externalError ?? localError) : null;
  const {
    id: instanceId,
    definitionId,
    title,
    scope,
    revision,
  } = session.instance;
  const queryConfig = session.result?.config ?? session.instance.config;
  const presentation = session.instance.config.presentation;
  // Keep live metadata without invalidating rendered records for query-only drafts.
  const resultInstance = useMemo(
    () =>
      instanceId !== undefined && queryConfig && presentation
        ? {
            id: instanceId,
            definitionId: definitionId,
            title: title,
            scope: scope,
            revision: revision,
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
  return (
    <>
      <RecordActionGuard disabled={stale} isCurrent={scopeCurrent}>
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
                configurable={configurable}
                isCurrent={selectionCurrent}
                refresh={refresh}
                onSelectionClear={() => run(tableOperations.clearSelection)}
                onColumnsChange={tableHandlers.onColumnsChange}
                onSortChange={tableHandlers.onSortChange}
                onCardChange={
                  !configurable || renderCard
                    ? undefined
                    : tableOperations.setCardConfig
                }
              />
            ),
            appliedFilter: displayedFilter,
            querying,
            selectedRowKeys: session.selectedRowKeys,
            ...tableOperations,
          }}
        />
      </RecordActionGuard>
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
        appliedFilter={displayedFilter}
        actionsDisabled={stale}
        isCurrent={actionsCurrent}
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
              label={paginationLabel}
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
    </>
  );
}
