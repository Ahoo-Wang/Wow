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
import type { RecordSession } from '../recordModel.js';
import type { RecordPaginationRenderContext } from '../recordReactTypes.js';
import type { ViewEngine } from '../ViewEngine.js';

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
  const { pagination } = session.instance.config;
  const paged = pagination.mode === 'paged';
  const pageCount =
    paged && session.total !== null
      ? Math.max(1, Math.ceil(session.total / pagination.size))
      : null;
  const navigable =
    session.queryStatus === 'success' && session.queryError === null;
  return {
    mode: pagination.mode,
    page: session.page,
    pageSize: pagination.size,
    pageCount,
    canNext:
      navigable &&
      (paged
        ? pageCount !== null && session.page < pageCount
        : session.nextCursor !== null),
    canPrevious: navigable && paged && session.page > 1,
    canChangePageSize:
      session.queryStatus !== 'loading' && session.queryError === null,
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
  const current = () => engine.getSnapshot().sessions[id];
  return {
    setPage(index) {
      const session = current();
      if (!session || session.instance.config.pagination.mode !== 'paged')
        return resolved;
      if (!Number.isSafeInteger(index) || index < 1)
        return engine.setPage(index, id);
      const policy = getRecordPaginationPolicy(session);
      if (
        session.queryStatus !== 'success' ||
        session.queryError ||
        (policy.pageCount !== null && index > policy.pageCount)
      )
        return resolved;
      return engine.setPage(index, id);
    },
    setPageSize(size) {
      const session = current();
      return session && getRecordPaginationPolicy(session).canChangePageSize
        ? engine.setPageSize(size, id)
        : resolved;
    },
    nextPage() {
      const session = current();
      if (!session || !getRecordPaginationPolicy(session).canNext)
        return resolved;
      return session.instance.config.pagination.mode === 'paged'
        ? engine.setPage(session.page + 1, id)
        : engine.nextPage(id);
    },
    previousPage() {
      const session = current();
      return session && getRecordPaginationPolicy(session).canPrevious
        ? engine.setPage(session.page - 1, id)
        : resolved;
    },
  };
}
