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

import { QueryErrorCodes } from '@ahoo-wang/wow-client';

/**
 * The rejections that say the descriptor may be behind (capabilities.md 7):
 * a query the descriptor admitted was refused for a capability, so the
 * service may have changed since it was read. The value rejections
 * (`VALUE_MISMATCH`, `NOT_COLLECTION`, `NOT_SINGLE_STRING`) are about what
 * was typed, and the decoding ones about what the engine sent; neither
 * says anything about the descriptor.
 */
const CAPABILITY_CODES: ReadonlySet<string> = new Set([
  QueryErrorCodes.UNKNOWN_FIELD,
  QueryErrorCodes.UNSUPPORTED_CAPABILITY,
  QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED,
  QueryErrorCodes.CURSOR_NOT_ALLOWED,
  QueryErrorCodes.ELEMENT_SCOPE_REQUIRED,
  QueryErrorCodes.PROTECTED_AGGREGATION,
  QueryErrorCodes.PROTECTED_COMPARISON,
  QueryErrorCodes.MISSING_KEY_REQUIRES_STRING,
  QueryErrorCodes.ANY_REQUIRES_SINGLE_VALUE,
  QueryErrorCodes.METRIC_FILTER_SEARCH,
  QueryErrorCodes.METRIC_FILTER_ELEMENT_MATCH,
  QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD,
  QueryErrorCodes.INCOMPLETE_PROJECTION,
  QueryErrorCodes.NOT_PROJECTABLE,
]);

/**
 * What the guard refuses without a code (wow-query's `QueryBudget`): a
 * budget crossed, named in the message (`HTTP list query limit[2000] must be
 * between 1 and 1000.`); an operator, a metric sort, an expansion or a
 * formula the entry keeps for itself (`… are not allowed.`); a count of
 * every record. Each of those the descriptor says, so a refusal means it is
 * behind.
 */
const BUDGET_REFUSAL =
  /\b(?:limit|size|window|nodes|values)\[\d+\]|expensive operators are not allowed|must not match all documents/;

/** What a rejection said, as far as this reads it. */
export interface Rejection {
  /** The violation's stable code, where the service named one. */
  code?: string;
  /** The service's own words. */
  reason: string;
  /** The HTTP status, where there was one. */
  status?: number;
}

/**
 * Whether a rejected query is a reason to check the source's descriptor
 * again at once, rather than at its next scheduled check (capabilities.md
 * 7, C5): a capability violation, or a budget refused without a code.
 */
export function checksDescriptorAgain(rejection: Rejection): boolean {
  if (rejection.code !== undefined) return CAPABILITY_CODES.has(rejection.code);
  return rejection.status === 400 && BUDGET_REFUSAL.test(rejection.reason);
}
