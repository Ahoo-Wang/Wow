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

import type { DatasetClient } from '@ahoo-wang/wow-client';
import type {
  DatasetCatalog,
  DatasetDirectoryEntry,
} from '@ahoo-wang/wow-view-engine';

/**
 * What a `DatasetCatalog` reads of a Wow **V10** service's datasets: the
 * directory (`GET /wow/dataset`) and each dataset's query client, which is a
 * `ViewSource` as it is. A host spreads it into the catalog it hands
 * `DatasetResources`, beside its own `overrides` and `wordings`:
 *
 * ```ts
 * const datasets = new DatasetClient({ fetcher });
 * new DatasetResources(engine, {
 *   catalogs: {
 *     ...datasetCatalogOf(datasets),
 *     wordings: new WowDatasetWordings({ fetcher }),
 *   },
 * });
 * ```
 *
 * A V10 directory lists only the datasets it serves and names no version of
 * them, so a changed dataset is told by its descriptor's version, which the
 * engine checks on its own schedule.
 */
export function datasetCatalogOf(
  client: DatasetClient,
): Pick<DatasetCatalog, 'list' | 'source'> {
  return {
    async list(options): Promise<DatasetDirectoryEntry[]> {
      return (await client.list(options)).map(entry => ({
        context: entry.context,
        dataset: entry.dataset,
        ...(entry.title === undefined ? {} : { title: entry.title }),
      }));
    },
    source: entry => client.dataset(entry.context, entry.dataset),
  };
}
