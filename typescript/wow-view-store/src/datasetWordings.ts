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
import { asc, cursorQuery, filter } from '@ahoo-wang/wow-client/dsl';
import type { CommandOptions, CommandResult } from '@ahoo-wang/wow-client';
import type {
  DatasetOverrides,
  DatasetWordingStore,
  StoredDatasetWording,
  ReadOptions,
  WriteOptions,
} from '@ahoo-wang/wow-view-engine';
import { revisionOf } from './views.js';
import { DatasetWordingCommandClient } from './generated/view-store/dataset_wording/commandClient.js';
import { DatasetWordingSnapshotQueryClient } from './generated/view-store/dataset_wording/queryClient.js';
import { guard, request } from './errors.js';
import { wordingOf } from './wire.js';

/** How a V10 {@link WowDatasetWordings} reaches the view store service: as a V10 `WowViewStore` does. */
export interface WowDatasetWordingsOptions {
  /**
   * The fetcher every request goes through, as the V10
   * `WowViewStoreOptions.fetcher`: its interceptors carry the caller and
   * `CoSec-App-Id`. The wording is the tenant's (an aggregate of the tenant,
   * not of an owner), so no request names a scope.
   */
  fetcher: Fetcher;
}

/**
 * The datasets one page of {@link WowDatasetWordings.list} reads: the
 * service's default cursor page limit over HTTP, as a view list's page.
 */
const LIST_PAGE = 100;

/**
 * The wording a tenant's administrators keep over its datasets in the V10
 * view store: one per dataset of the caller's tenant and
 * application, the id derived by the service from the two. Every reader of
 * the tenant reads it; the service decides who writes it (the role that
 * writes the owner `(shared)`, `view-store.shared-writer` as shipped).
 *
 * A host hands it to the datasets it registers as their store of wording
 * (`DatasetCatalog.wordings`, D91). The key is the dataset's definition id,
 * `{context}.{dataset}`. Every rejection is a `ViewStoreError`, read as a
 * V10 `WowViewStore`'s are.
 */
export class WowDatasetWordings implements DatasetWordingStore {
  private readonly commands: DatasetWordingCommandClient;
  private readonly snapshots: DatasetWordingSnapshotQueryClient;

  constructor(options: WowDatasetWordingsOptions) {
    const client = { fetcher: options.fetcher };
    this.commands = new DatasetWordingCommandClient(client);
    this.snapshots = new DatasetWordingSnapshotQueryClient(client);
  }

  /**
   * Every dataset's wording of the caller's tenant and application, by
   * dataset. A cleared one is listed too, saying nothing: its revision is
   * what the next write expects.
   *
   * Read whole, a page at a time by the snapshot model's cursor, oldest
   * first, until the service has no next page. A wording left unread would
   * read as never written — revision `'0'` — and every save of it would be
   * refused as a conflict nobody could clear (#5546); the port promises
   * every dataset's, so no count of them cuts it short. The caller's signal
   * stops the reading between pages and within one.
   */
  list({ signal }: ReadOptions = {}): Promise<
    ReadonlyMap<string, StoredDatasetWording>
  > {
    return guard(async () => {
      const wordings = new Map<string, StoredDatasetWording>();
      let cursor: string | null = null;
      do {
        const page = await this.page(cursor, signal);
        for (const { version, state } of page.list)
          wordings.set(state.dataset, {
            wording: wordingOf(state),
            revision: revisionOf(version),
          });
        cursor = page.nextCursor;
        signal?.throwIfAborted();
      } while (cursor !== null);
      return wordings;
    });
  }

  /** One page of the wordings, the one after `cursor`; the first without one. */
  private page(cursor: string | null, signal: AbortSignal | undefined) {
    return request(() =>
      this.snapshots.cursor(
        cursorQuery({
          filter: filter.matchAll(),
          sort: [asc('firstEventTime')],
          size: LIST_PAGE,
          cursor,
        }),
        signal === undefined ? {} : { signal },
      ),
    );
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
    context: WriteOptions,
  ): Promise<string> {
    return guard(async () => {
      const result = await request(() =>
        this.commands.setDatasetWording(
          {
            dataset,
            ...(wording.title === undefined ? {} : { title: wording.title }),
            ...(wording.recordNoun === undefined
              ? {}
              : { recordNoun: wording.recordNoun }),
            ...(wording.fields === undefined
              ? {}
              : {
                  fields: wording.fields as unknown as Record<string, unknown>,
                }),
          },
          optionsOf(revision, context),
        ),
      );
      return versionAfter(result, revision);
    });
  }

  /**
   * Clears a dataset's wording at the expected `revision`: its derived words
   * stand again. Answers the new revision.
   */
  clear(
    dataset: string,
    revision: string,
    context: WriteOptions,
  ): Promise<string> {
    return guard(async () => {
      const result = await request(() =>
        this.commands.clearDatasetWording(
          { dataset },
          optionsOf(revision, context),
        ),
      );
      return versionAfter(result, revision);
    });
  }
}

function optionsOf(
  revision: string,
  { requestId, signal }: WriteOptions,
): CommandOptions {
  return {
    requestId,
    expectedVersion: Number(revision),
    ...(signal === undefined ? {} : { signal }),
  };
}

/** The revision a write left: its version, or the one it expected moved on. */
function versionAfter(result: CommandResult, revision: string): string {
  return revisionOf(result.version ?? Number(revision) + 1);
}
