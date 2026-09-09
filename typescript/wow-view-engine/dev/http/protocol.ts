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

import { ViewServiceError } from '@ahoo-wang/fetcher-view-engine';
import { encodeViewResourceId as encodeId } from '../../src/record/viewServiceContract.js';
/** Experimental wire mapping; not part of the published ViewHost contract. */
export const VIEW_SERVICE_STATUS = {
  INVALID_ARGUMENT: 400,
  UNAUTHENTICATED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  REVISION_CONFLICT: 412,
  PRECONDITION_REQUIRED: 428,
  CORRUPT_STATE: 500,
  UNAVAILABLE: 503,
  UNKNOWN_OUTCOME: 503,
} as const;

export function encodeViewResourceId(id: unknown): string {
  try {
    return encodeId(id);
  } catch (error) {
    throw new ViewServiceError(
      'INVALID_ARGUMENT',
      error instanceof Error ? error.message : '无效资源 ID',
    );
  }
}
