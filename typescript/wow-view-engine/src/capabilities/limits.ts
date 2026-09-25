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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import { DEFAULT_RUNTIME_LIMITS, type RuntimeLimits } from '../model/index.js';

/**
 * The limits that bound what a source accepts rather than what the engine
 * does (capabilities.md 4.5): a source with a descriptor says them, and the
 * host may only lower them.
 */
export const SOURCE_LIMIT_NAMES = [
  'maxPageSize',
  'maxPageWindow',
  'maxAnalysisRows',
  'maxQueryFilterNodes',
  'maxFilterValues',
] as const;

export type SourceLimitName = (typeof SOURCE_LIMIT_NAMES)[number];

/**
 * The limits one runtime over a source runs under.
 *
 * Without a descriptor they are the host's over the defaults, exactly as
 * before descriptors existed: the source budgets default to what a Wow
 * service admits at its own defaults (D42). With one, each source budget
 * is the descriptor's, lowered to the host's where the host set one —
 * a host that raised its server's guard need not raise the engine's too.
 * `null` in the descriptor is no bound: a window or a filter size that
 * never stops anything, and a page size at the largest rung the engine
 * ever offers, since a page is still asked for in one piece.
 */
export function sourceLimits(
  host: Partial<RuntimeLimits> | undefined,
  descriptor: QueryModelDescriptor | null,
): RuntimeLimits {
  const base: RuntimeLimits = host
    ? { ...DEFAULT_RUNTIME_LIMITS, ...host }
    : DEFAULT_RUNTIME_LIMITS;
  if (!descriptor) return base;

  const { limits } = descriptor;
  const largestRung = Math.max(
    base.defaultPageSize,
    ...base.pageSizes,
    ...base.cardPageSizes,
  );
  const described: Record<SourceLimitName, number> = {
    maxPageSize: limits.maxPageSize ?? largestRung,
    maxPageWindow: limits.maxPageWindow ?? Number.POSITIVE_INFINITY,
    maxAnalysisRows: limits.aggregation.maxLimit,
    maxQueryFilterNodes: limits.maxFilterNodes ?? Number.POSITIVE_INFINITY,
    maxFilterValues: limits.maxFilterValues ?? Number.POSITIVE_INFINITY,
  };
  const next: RuntimeLimits = { ...base };
  for (const name of SOURCE_LIMIT_NAMES) {
    const own = host?.[name];
    next[name] =
      own === undefined ? described[name] : Math.min(own, described[name]);
  }
  return next;
}
