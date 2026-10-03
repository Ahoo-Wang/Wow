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

import { AGGREGATION_LIMITS } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  type AnalysisCapability,
  type RuntimeLimits,
} from '../model/index.js';

const DEFAULT_LIMIT = 100;

/**
 * The range 「前 N 组」 may take, and the N it stands for when nobody said one.
 *
 * `max` is the lowest of three ceilings — the capability's `maxLimit`, the
 * runtime's `maxAnalysisRows` and Wow's own `AGGREGATION_LIMITS.MAX_LIMIT` —
 * because a declaration may only lower what the layers under it allow; the
 * least is 1, since Wow refuses a limit under it. It is also the most rows
 * any query the engine compiles asks for — the probe row
 * (`analysisProbeLimit`) and the split's whole (`splitWholeConfig`)
 * included — since `maxAnalysisRows` is what the server admits: a Wow
 * server's HTTP guard refuses more than 1,000 by default (D42).
 *
 * `fallback` is where a new view starts, and what an emptied 「前 N 组」
 * field means: the model has no "no limit" (Wow answers at most `MAX_LIMIT`
 * rows whatever is asked), so the honest reading of a blank is the number a
 * view would have started at.
 *
 * One function for the three readers — the default config, the admission
 * rule and the tray's field — so the bounds a field says out loud are the
 * ones Apply is refused by, never a second copy of them.
 */
export interface AnalysisLimitBounds {
  max: number;
  fallback: number;
}

export function limitBounds(
  capability: Pick<AnalysisCapability, 'limits'> | undefined,
  limits: Pick<RuntimeLimits, 'maxAnalysisRows'> = DEFAULT_RUNTIME_LIMITS,
): AnalysisLimitBounds {
  const max = Math.min(
    capability?.limits?.maxLimit ?? Number.POSITIVE_INFINITY,
    limits.maxAnalysisRows,
    AGGREGATION_LIMITS.MAX_LIMIT,
  );
  return {
    max,
    fallback: Math.min(capability?.limits?.defaultLimit ?? DEFAULT_LIMIT, max),
  };
}
