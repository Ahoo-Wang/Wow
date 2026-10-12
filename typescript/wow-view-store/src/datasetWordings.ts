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

import { ResultExtractors, type Fetcher } from '@ahoo-wang/fetcher';
import { listQuery, filter } from '@ahoo-wang/wow-client';
import type {
  DatasetOverrides,
  DatasetWordingStore,
  StoredDatasetWording,
  WriteContext,
} from '@ahoo-wang/wow-view-engine';
import { failureOf } from './errors.js';
import { pathAt, SCOPE } from './paths.js';
import { revisionOf, type CommandResultBody } from './wire.js';
import { guard, writeHeaders } from './wowViewStore.js';

/** How a {@link WowDatasetWordings} reaches the view store: as a `WowViewStore` does. */
export interface WowDatasetWordingsOptions {
  /**
   * The fetcher every request goes through, as {@link WowViewStoreOptions.fetcher}:
   * its interceptors fill `{tenantId}` and send `CoSec-App-Id`. The
   * wording is the tenant's, so the store always names the owner `(shared)`.
   */
  fetcher: Fetcher;
}

/** The most datasets one application's wording is read for at once. */
const LIST_LIMIT = 1000;

const ROUTES = {
  /** `PUT` sets, `DELETE` clears; under `(shared)` only. */
  wording: `${SCOPE}/datasets/{dataset}/wording`,
  list: `${SCOPE}/dataset_wording/snapshot/list`,
} as const;

/** A wording's snapshot as the list answers it. */
interface WordingSnapshotBody {
  version: number;
  state: {
    dataset: string;
    title?: string | null;
    recordNoun?: string | null;
    fields?: DatasetOverrides['fields'] | null;
  };
}

/**
 * The wording a tenant's administrators keep over its datasets in the Wow
 * view store (view engine D86): one per dataset of the caller's tenant and
 * application, under `owner/(shared)`. The gateway decides who may write it.
 *
 * A host hands it to the datasets it registers as their store of wording
 * (`DatasetCatalog.wordings`, D91): `DatasetResources` reads it before each
 * refresh, registers each dataset in its stored words, and gives the view
 * manager an editor of them. The key is the dataset's definition id,
 * `{context}.{dataset}`.
 *
 * ```ts
 * const resources = new DatasetResources(engine, {
 *   list: () => directory.list(),
 *   source: entry => directory.source(entry),
 *   wordings: new WowDatasetWordings({ fetcher }),
 * });
 * ```
 *
 * Every rejection is a `ViewStoreError`, read as a `WowViewStore`'s are.
 */
export class WowDatasetWordings implements DatasetWordingStore {
  private readonly fetcher: Fetcher;

  constructor(options: WowDatasetWordingsOptions) {
    this.fetcher = options.fetcher;
  }

  /** Every dataset's wording of the caller's tenant and application, by dataset. */
  list(
    signal?: AbortSignal,
  ): Promise<ReadonlyMap<string, StoredDatasetWording>> {
    return guard(async () => {
      const response = await this.send('POST', ROUTES.list, {
        body: listQuery({ filter: filter.matchAll(), limit: LIST_LIMIT }),
        signal,
      });
      const snapshots = (await response.json()) as WordingSnapshotBody[];
      return new Map(
        snapshots.map(({ version, state }) => [
          state.dataset,
          { wording: wordingOf(state), revision: revisionOf(version) },
        ]),
      );
    });
  }

  /**
   * Writes a dataset's wording at the expected `revision` (`'0'` for one never
   * written), whole: what it leaves out is no longer said. Answers the new
   * revision.
   */
  set(
    dataset: string,
    wording: DatasetOverrides,
    revision: string,
    context: WriteContext,
  ): Promise<string> {
    return guard(async () => {
      const response = await this.send('PUT', ROUTES.wording, {
        dataset,
        body: {
          ...(wording.title === undefined ? {} : { title: wording.title }),
          ...(wording.recordNoun === undefined
            ? {}
            : { recordNoun: wording.recordNoun }),
          ...(wording.fields === undefined ? {} : { fields: wording.fields }),
        },
        headers: writeHeaders(context, revision),
        signal: context.signal,
      });
      const result = (await response.json()) as CommandResultBody;
      return revisionOf(result.aggregateVersion ?? Number(revision) + 1);
    });
  }

  /** Clears a dataset's wording at the expected `revision`: its derived words stand again. */
  clear(
    dataset: string,
    revision: string,
    context: WriteContext,
  ): Promise<void> {
    return guard(async () => {
      await this.send('DELETE', ROUTES.wording, {
        dataset,
        body: {},
        headers: writeHeaders(context, revision),
        signal: context.signal,
      });
    });
  }

  private async send(
    method: string,
    url: string,
    {
      dataset,
      body,
      headers,
      signal,
    }: {
      dataset?: string;
      body?: unknown;
      headers?: Record<string, string>;
      signal?: AbortSignal;
    },
  ): Promise<Response> {
    try {
      return await this.fetcher.request<Response>(
        {
          url,
          method,
          urlParams: {
            path: pathAt('shared', dataset === undefined ? {} : { dataset }),
          },
          body: body as Record<string, unknown> | undefined,
          headers,
          signal,
        },
        { resultExtractor: ResultExtractors.Response },
      );
    } catch (error) {
      throw await failureOf(error);
    }
  }
}

/** The engine's overrides from a stored wording: what is stored, nothing more. */
function wordingOf(state: WordingSnapshotBody['state']): DatasetOverrides {
  return {
    ...(state.title == null ? {} : { title: state.title }),
    ...(state.recordNoun == null ? {} : { recordNoun: state.recordNoun }),
    ...(state.fields == null ? {} : { fields: state.fields }),
  };
}
