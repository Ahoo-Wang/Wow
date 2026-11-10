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

import { type AggregationQuery } from '../../../src/dsl';

/**
 * The register of Wow's own query rules.
 *
 * Wow states each of these by throwing. This package speaks the same protocol,
 * so every one of them is either mirrored here, satisfied by the shape of the
 * builders, or left to the server — and which of the three, for each rule, is
 * written down rather than inferred.
 *
 * `wow` is the message Wow throws, verbatim, and is the key: search Wow's
 * source for it to find the rule, and search this file for a message Wow
 * throws to see whether it is accounted for.
 *
 * `throws` is what this package says, which is not always the same wording.
 * Recording both keeps the divergence visible: `filter.gt('a', null)` is
 * refused for being null, but the message it gives is the scalar one.
 */
export interface ConformanceRule {
  /** The message Wow throws, verbatim. */
  wow: string;
  /** Where Wow states it. */
  source: string;
  /** An input that breaks the rule. */
  violate?: () => unknown;
  /** What this package throws for that input, when it differs from `wow`. */
  throws?: string;
  /** Why no input can break it here. */
  byConstruction?: string;
  /** Why this package does not mirror it. */
  serverOnly?: string;
}

export const anyMetrics = (...metrics: unknown[]) =>
  metrics as unknown as AggregationQuery['metrics'];
