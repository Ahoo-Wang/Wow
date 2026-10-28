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

import type { RecordPaging } from '../../record/index.js';
import type { MessageFormatters } from '../kit/MessagesProvider.js';

/**
 * The number a result's bar and its announcement both say: the source's
 * total when it gave one — "at least" when that is a lower bound, as Wow's
 * default `BOUNDED` count is past its budget — and the rows that did arrive
 * when it gave none (a cursor source, or a route that counts nothing).
 */
export function resultCount(
  messages: Pick<MessageFormatters, 'label'>,
  paging: RecordPaging | null,
  rows: number,
): string {
  const total = paging?.mode === 'paged' ? paging.total : undefined;
  if (total === undefined)
    return messages.label('label.pagination.on-page', { count: rows });
  return paging?.mode === 'paged' && paging.exact === false
    ? messages.label('label.pagination.total-at-least', { count: total })
    : messages.label('label.pagination.total', { count: total });
}
