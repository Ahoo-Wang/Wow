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

import { RecordActionGuard } from '../RecordActionGuard.js';
import { sameFilterQuery } from '../../filter/filterTree.js';
import { useMemo } from 'react';
import type {
  RecordSession,
  RecordViewDefinition,
} from '../../contracts/viewModel.js';
import type { RecordExtensions } from '../recordReactTypes.js';
import { RecordRendererBoundary } from '../RecordRendererBoundary.js';

export function RecordActions({
  kind,
  definition,
  session,
  extensions,
  refresh,
  isCurrent,
}: {
  kind: 'global' | 'toolbar';
  definition: RecordViewDefinition;
  session: RecordSession;
  extensions?: RecordExtensions;
  refresh(): Promise<void>;
  isCurrent?(): boolean;
}) {
  const { id, definitionId, title, scope, revision } = session.instance;
  const config = session.result?.config ?? session.instance.config;
  const instance = useMemo(
    () => ({
      id,
      definitionId,
      title,
      scope,
      revision,
      kind: 'record' as const,
      config,
    }),
    [id, definitionId, title, scope, revision, config],
  );
  const querying =
    session.queryStatus === 'loading' || session.queryStatus === 'waiting';
  const filter = session.result?.filter ?? session.appliedFilter;
  const disabled = !sameFilterQuery(filter, session.appliedFilter);
  const reference = definition.record.recordActions?.[kind];
  if (!reference) return null;
  const registry =
    kind === 'global' ? extensions?.globalActions : extensions?.toolbarActions;
  const Actions =
    registry && Object.prototype.hasOwnProperty.call(registry, reference.name)
      ? registry[reference.name]
      : undefined;
  const label = kind === 'global' ? '全局操作' : '工具栏操作';
  return (
    <div
      role="group"
      aria-label={label}
      key={`${kind}:${id}`}
      className="fve:flex fve:flex-wrap fve:items-center fve:gap-2"
    >
      {Actions ? (
        <RecordActionGuard disabled={disabled} isCurrent={isCurrent}>
          <RecordRendererBoundary
            label={label}
            // Recovery follows render inputs, not event-handler identity.
            resetKey={[
              Actions,
              definition,
              instance,
              session.appliedFilter,
              reference.options,
              session.selectedRowKeys,
              querying,
            ]}
          >
            <Actions
              definition={definition}
              instance={instance}
              filter={filter}
              isCurrent={isCurrent}
              sort={instance.config.sort}
              selectedRowKeys={session.selectedRowKeys}
              querying={querying}
              options={reference.options}
              refresh={refresh}
            />
          </RecordRendererBoundary>
        </RecordActionGuard>
      ) : (
        <p role="alert" className="fve:text-sm fve:text-destructive">
          未注册{label}：{reference.name}
        </p>
      )}
    </div>
  );
}
