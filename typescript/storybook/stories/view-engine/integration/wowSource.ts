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
import {
  QueryClientFactory,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/wow-client';
import type { ViewSource } from '@ahoo-wang/wow-view-engine';

// Step 2a: where the rows come from — a Wow service's query routes, through
// wow-client. The engine sends Wow queries (paged, cursor, aggregation) and
// never anything else, so the snapshot query client is the source as it is.
export function wowOrderSource(fetcher: Fetcher): ViewSource {
  const factory = new QueryClientFactory({
    contextAlias: 'example',
    aggregateName: 'order',
    resourceAttribution: ResourceAttributionPathSpec.NONE,
    fetcher,
  });
  const snapshots = factory.createSnapshotQueryClient();
  // What the service admits on the store it runs on: the engine reads it
  // before the first query and offers only what the server will answer.
  const descriptors = factory.createQueryDescriptorClient();
  return {
    paged: (query, attributes, abort) =>
      snapshots.paged(query, attributes, abort),
    cursor: (query, attributes, abort) =>
      snapshots.cursor(query, attributes, abort),
    aggregate: (query, attributes, abort) =>
      snapshots.aggregate(query, attributes, abort),
    describe: (previous, attributes, abort) =>
      descriptors.describeSnapshot(previous, attributes, abort),
  };
}
