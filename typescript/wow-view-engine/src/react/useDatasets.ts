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

import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  DatasetDuplicate,
  DatasetResource,
  DatasetResources,
} from '../runtime/datasetResources.js';

/** The datasets a `DatasetResources` serves, as `useDatasets` reads them. */
export interface DatasetsState {
  /** The datasets served, in the directories' order, as read last. */
  datasets: readonly DatasetResource[];
  /** The datasets the last read left out because an earlier catalog serves their id. */
  duplicates: readonly DatasetDuplicate[];
  /** Whether a read is under way: the first, or one `key` or `refresh` asked for. */
  loading: boolean;
  /** Why the last read failed (no catalog could be listed); what was read before stays. */
  error: unknown;
  /** Reads the directories again. */
  refresh(): void;
}

/** When `useDatasets` reads the directories. */
export interface DatasetsReading {
  /**
   * What the datasets served depend on, such as the caller in force, since
   * another caller sees other datasets: a change of it reads them again.
   * Left out, they are read once, as the hook mounts.
   */
  key?: string;
  /**
   * Reads nothing while set — no caller yet, say — and keeps what was read
   * last; clearing it reads at once.
   */
  paused?: boolean;
}

/**
 * Keeps a host in step with the datasets `resources` serves: it reads
 * them when it mounts, whenever `reading.key` changes and on `refresh`,
 * and not at all while `reading.paused` is set. A read that lands after
 * another was asked for is dropped.
 */
export function useDatasets(
  resources: Pick<DatasetResources, 'refresh'>,
  { key = '', paused = false }: DatasetsReading = {},
): DatasetsState {
  const [round, setRound] = useState(0);
  const asked = paused ? null : `${key}#${round}`;
  const [read, setRead] = useState<{
    for: string | null;
    datasets: readonly DatasetResource[];
    duplicates: readonly DatasetDuplicate[];
    error: unknown;
  }>({ for: null, datasets: [], duplicates: [], error: null });
  useEffect(() => {
    if (asked === null) return;
    let live = true;
    const reading = new AbortController();
    resources.refresh({ signal: reading.signal }).then(
      ({ datasets, duplicates }) => {
        if (live) setRead({ for: asked, datasets, duplicates, error: null });
      },
      (error: unknown) => {
        if (live) setRead(current => ({ ...current, for: asked, error }));
      },
    );
    return () => {
      live = false;
      reading.abort();
    };
  }, [resources, asked]);
  const refresh = useCallback(() => setRound(value => value + 1), []);
  return useMemo(
    () => ({
      datasets: read.datasets,
      duplicates: read.duplicates,
      error: read.error,
      loading: asked !== null && read.for !== asked,
      refresh,
    }),
    [read, asked, refresh],
  );
}
