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

import type { RecordData, ViewSource } from '@ahoo-wang/wow-view-engine';
import {
  memorySource,
  type MemorySourceOptions,
} from '@ahoo-wang/wow-view-engine/testing';

/**
 * The stories' data source: rows held in memory, answered the way a Wow
 * service answers each query the engine really sends — filtered, sorted,
 * paged and aggregated, nothing pre-aggregated — by the package's own
 * in-memory source (`@ahoo-wang/wow-view-engine/testing`, D65), whose
 * semantics its suites hold to Wow's `FilterSemantics` matrix and TCK.
 *
 * A story's rows never change under it, so a repeated aggregation — a
 * board's panels and a metric card's comparison send the same totals more
 * than once — is answered from memory. `timeField` and `now` are the
 * source's own options: the time column a large set is kept in order of, and
 * the clock `BEFORE_NOW` / `AFTER_NOW` read.
 */
export function rowSource(
  rows: readonly RecordData[],
  options: Pick<MemorySourceOptions, 'timeField' | 'now'> = {},
): ViewSource {
  return memorySource(rows, { ...options, remember: true });
}
