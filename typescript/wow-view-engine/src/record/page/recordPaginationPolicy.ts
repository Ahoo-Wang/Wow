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

import type { DeepReadonly } from '../../lib/types.js';
import type { RecordSession } from '../../contracts/viewModel.js';
import type { RecordPaginationRenderContext } from '../recordReactTypes.js';
import type { ViewEngine } from '../../engine/ViewEngine.js';

export type RecordPaginationPolicy = Pick<
  RecordPaginationRenderContext,
  | 'mode'
  | 'page'
  | 'pageSize'
  | 'pageCount'
  | 'canNext'
  | 'canPrevious'
  | 'canChangePageSize'
>;

export function getRecordPaginationPolicy(
  session: DeepReadonly<RecordSession>,
): RecordPaginationPolicy {
  const scope = session.result ?? session.queryAttempt;
  const { pagination } = scope?.config ?? session.instance.config;
  const page = scope?.page ?? session.page;
  const paged = pagination.mode === 'paged';
  const pageCount =
    paged && session.total !== null
      ? Math.max(1, Math.ceil(session.total / pagination.size))
      : null;
  const navigable =
    session.queryStatus === 'success' && session.queryError === null;
  return {
    mode: pagination.mode,
    page,
    pageSize: pagination.size,
    pageCount,
    canNext:
      navigable &&
      (paged
        ? pageCount !== null && page < pageCount
        : session.nextCursor !== null),
    canPrevious: navigable && paged && page > 1,
    canChangePageSize:
      session.queryStatus !== 'loading' &&
      session.queryStatus !== 'waiting' &&
      session.queryError === null,
  };
}

const resolved = Promise.resolve();

export function bindRecordPagination(
  engine: ViewEngine,
  id: string,
): Pick<
  RecordPaginationRenderContext,
  'setPage' | 'setPageSize' | 'nextPage' | 'previousPage'
> {
  return bindRecordPaginationCommands(
    () => {
      const session = engine.getSnapshot().sessions[id];
      return session?.kind === 'record' ? session : undefined;
    },
    () => engine.record(id),
  );
}

export function bindRecordPaginationCommands(
  current: () => RecordSession | undefined,
  commands: () => ReturnType<ViewEngine['record']>,
): Pick<
  RecordPaginationRenderContext,
  'setPage' | 'setPageSize' | 'nextPage' | 'previousPage'
> {
  return {
    setPage(index) {
      const session = current();
      if (!session) return resolved;
      const policy = getRecordPaginationPolicy(session);
      if (policy.mode !== 'paged') return resolved;
      if (!Number.isSafeInteger(index) || index < 1)
        return commands().setPage(index);
      if (
        session.queryStatus !== 'success' ||
        session.queryError ||
        (policy.pageCount !== null && index > policy.pageCount)
      )
        return resolved;
      return commands().setPage(index);
    },
    setPageSize(size) {
      const session = current();
      return session && getRecordPaginationPolicy(session).canChangePageSize
        ? commands().setPageSize(size)
        : resolved;
    },
    nextPage() {
      const session = current();
      if (!session) return resolved;
      const policy = getRecordPaginationPolicy(session);
      if (!policy.canNext) return resolved;
      return policy.mode === 'paged'
        ? commands().setPage(policy.page + 1)
        : commands().nextPage();
    },
    previousPage() {
      const session = current();
      const policy = session && getRecordPaginationPolicy(session);
      return policy?.canPrevious
        ? commands().setPage(policy.page - 1)
        : resolved;
    },
  };
}
