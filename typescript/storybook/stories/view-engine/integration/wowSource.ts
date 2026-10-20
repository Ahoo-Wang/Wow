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

import type { Fetcher } from '@ahoo-wang/fetcher';
import { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import type { ViewSource } from '@ahoo-wang/wow-view-engine';
import { viewSourceOf } from '@ahoo-wang/wow-view-store';

// Step 2a: where the rows come from — a Wow service's query route, through
// wow-client. The engine sends queries (paged, cursor, aggregation) and asks
// what the route admits on the store it runs on, so it offers only what the
// service will answer; `viewSourceOf` hands it the snapshot query client.
export function wowOrderSource(fetcher: Fetcher): ViewSource {
  return viewSourceOf(
    new SnapshotQueryClient({ context: 'example', name: 'order' }, { fetcher }),
  );
}
