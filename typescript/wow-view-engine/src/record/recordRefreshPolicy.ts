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

import type { RecordSession } from './recordModel.js';

/** Domain guards shared by the query command and its React controls. */
export function getRecordRefreshBlockReason(session: RecordSession) {
  if (session.requiresReload) return 'reload';
  if (session.queryStatus === 'error') return 'error';
  if (session.writeStatus !== 'idle') return 'write';
  if (session.filterPending) return 'filter';
  if (session.selectedRowKeys.length) return 'selection';
  if (session.queryStatus !== 'success') return 'query';
  if (
    session.instance.config.presentation.layout === 'table' &&
    session.allSummary.status === 'loading'
  )
    return 'summary';
  if (session.instance.config.pagination.mode === 'cursor' && session.page > 1)
    return 'cursor';
  if (session.refreshing) return 'refresh';
  return null;
}
