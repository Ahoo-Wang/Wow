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

import type {
  DatasetClient,
  QueryModelClient,
  RequestOptions,
} from '@ahoo-wang/wow-client';
import type {
  AggregationQuery,
  CursorQuery,
  FilterPagedQuery,
  PagedQuery,
} from '@ahoo-wang/wow-client/dsl';
import type {
  DatasetCatalog,
  DatasetDirectoryEntry,
  RecordData,
  ViewSource,
} from '@ahoo-wang/wow-view-engine';

/**
 * The view engine's `ViewSource` over one query route of a Wow **V10**
 * service: a generated `…SnapshotQueryClient`, a `QueryModelClient`, or a
 * dataset's (`DatasetClient.dataset(…)`). The engine's port takes an
 * `AbortController` rather than request options, reads the total as a plain
 * count, and wants the descriptor's version with every read. This is the
 * whole adapter.
 *
 * A page counts its total as the service does by default, `BOUNDED`
 * (D150): exact up to the budget's limit, and the limit itself past it. A
 * V10 service refuses an exact total over HTTP, so the engine, which reads
 * the total as the count of matches, shows a count past the limit as the
 * limit (an engine follow-up: say "at least"). The engine's `attributes`
 * have no counterpart on a V10 request and are not sent.
 *
 * ```ts
 * const engine = new ViewEngine({
 *   store: new WowViewStore({ fetcher }),
 *   resources: [
 *     { definition: orders, source: viewSourceOf(new OrderSnapshotQueryClient({ fetcher })) },
 *   ],
 * });
 * ```
 */
export function viewSourceOf<F extends string>(
  client: QueryModelClient<object, F>,
): ViewSource {
  return {
    async paged(query, _attributes, abort) {
      const page = await client.paged(
        query as FilterPagedQuery<F> as PagedQuery<F>,
        optionsOf(abort),
      );
      return {
        total: page.total ?? page.list.length,
        list: [...page.list] as RecordData[],
      };
    },
    async cursor(query, _attributes, abort) {
      const page = await client.cursor(
        query as CursorQuery<F>,
        optionsOf(abort),
      );
      return { list: page.list as RecordData[], nextCursor: page.nextCursor };
    },
    aggregate(query, _attributes, abort) {
      return client.aggregate<RecordData>(
        query as AggregationQuery<F, string>,
        optionsOf(abort),
      );
    },
    async describe(previous, _attributes, abort) {
      const read = await client.describe(previous, optionsOf(abort));
      if (read.notModified) return read;
      return {
        notModified: false,
        descriptor: read.descriptor,
        version: read.version ?? read.descriptor.version,
      };
    },
  };
}

/**
 * What a `DatasetCatalog` reads of a Wow **V10** service's datasets: the
 * directory (`GET /wow/dataset`) and each dataset's query route as a
 * `ViewSource` ({@link viewSourceOf}). A host spreads it into the catalog it
 * hands `DatasetResources`, beside its own `overrides` and `wordings`:
 *
 * ```ts
 * const datasets = new DatasetClient({ fetcher });
 * new DatasetResources(engine, {
 *   ...datasetCatalogOf(datasets),
 *   wordings: new WowDatasetWordings({ fetcher }),
 * });
 * ```
 *
 * A V10 directory entry names no version, and lists only datasets it serves:
 * each is given `LISTED`, so `DatasetResources` (which reads `null` as a
 * dataset not servable) registers it, and a dataset is told changed by its
 * descriptor's version, which the engine checks on its own schedule.
 */
export function datasetCatalogOf(
  client: DatasetClient,
): Pick<DatasetCatalog, 'list' | 'source'> {
  return {
    async list(): Promise<DatasetDirectoryEntry[]> {
      return (await client.list()).map(entry => ({
        contextName: entry.context,
        name: entry.dataset,
        title: entry.title ?? null,
        version: LISTED,
      }));
    },
    source: entry =>
      viewSourceOf(client.dataset(entry.contextName, entry.name)),
  };
}

/** The version every dataset a V10 directory lists is given (`datasetCatalogOf`). */
const LISTED = 'listed';

/** A V10 request's options from the engine's controller. */
function optionsOf(abort: AbortController | undefined): RequestOptions {
  return abort === undefined ? {} : { signal: abort.signal };
}
