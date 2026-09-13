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

import { useCallback, useState, useSyncExternalStore } from 'react';
import type { DataViewPosition } from '../engine/ViewEngine.js';
import type {
  AnalysisSession,
  RecordSession,
  ViewDefinition,
} from '../contracts/viewModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { ViewExtensions } from './viewReactTypes.js';
import { RecordContent } from '../record/RecordContent.js';
import { AnalysisResult } from '../analysis/AnalysisResultView.js';
import { sameFilterQuery } from '../filter/filterTree.js';
import { message } from '../lib/snapshot.js';

/** A released position renders nothing, including during parent/child subscription teardown. */
const emptySubscription = () => () => {};
export function useDataViewSession(position?: DataViewPosition) {
  const read = useCallback((): RecordSession | AnalysisSession | undefined => {
    try {
      return position?.getSnapshot();
    } catch {
      return undefined;
    }
  }, [position]);
  return useSyncExternalStore(
    position?.subscribe ?? emptySubscription,
    read,
    read,
  );
}

/** Result rendering and query recovery shared by dashboard cards and independent embeds. */
export function DataViewContent({
  position,
  definition,
  extensions,
  compilers,
  label,
}: {
  position: DataViewPosition;
  definition: DeepReadonly<ViewDefinition>;
  extensions?: ViewExtensions;
  compilers: FilterCompilerRegistry;
  label?: string;
}) {
  const session = useDataViewSession(position);
  const [mode, setMode] = useState<'analysis' | 'table' | null>(null);
  const [error, setError] = useState<string | null>(null);
  function run(action: () => void | Promise<void>) {
    setError(null);
    try {
      void Promise.resolve(action()).catch(reason => setError(message(reason)));
    } catch (reason) {
      setError(message(reason));
    }
  }
  if (!session) return null;
  if (
    session.kind === 'record' &&
    position.kind === 'record' &&
    definition.record
  )
    return (
      <>
        {(session.queryStatus === 'loading' ||
          session.queryStatus === 'waiting') && (
          <p role="status" className="fve:px-4 fve:text-sm">
            {session.queryStatus === 'waiting' ? '等待查询' : '正在查询'}
          </p>
        )}
        {session.result &&
          !sameFilterQuery(session.result.filter, session.appliedFilter) && (
            <p role="status" className="fve:px-4 fve:text-sm">
              上次成功结果
            </p>
          )}
        <RecordContent
          session={session}
          definition={{ ...definition, record: definition.record }}
          commands={position.commands}
          paginationLabel={label ? `${label}记录分页` : undefined}
          extensions={extensions}
          selectable={false}
          configurable={false}
          getSnapshot={() => {
            try {
              return position.getSnapshot();
            } catch {
              return undefined;
            }
          }}
        />
      </>
    );
  if (session.kind !== 'analysis' || position.kind !== 'analysis') return null;
  const querying =
    session.queryStatus === 'loading' || session.queryStatus === 'waiting';
  return (
    <AnalysisResult
      label={label ? `${label}分析结果` : undefined}
      compact
      active
      session={session}
      definition={definition}
      compilers={compilers}
      mode={
        mode ??
        (session.instance.config.presentation.layout === 'table'
          ? 'table'
          : 'analysis')
      }
      canRun={!querying && session.queryValid}
      localError={error ?? undefined}
      onRun={() => run(() => position.commands.run())}
      onSortChange={sort => run(() => position.commands.setSort(sort))}
      onModeChange={setMode}
    />
  );
}
