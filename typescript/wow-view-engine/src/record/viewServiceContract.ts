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

import type {
  ViewInstance,
  ViewInstancePermissions,
} from '../contracts/viewModel.js';

export type ViewServiceErrorCode =
  | 'INVALID_ARGUMENT'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'REVISION_CONFLICT'
  | 'PRECONDITION_REQUIRED'
  | 'CORRUPT_STATE'
  | 'UNAVAILABLE'
  | 'UNKNOWN_OUTCOME';
export class ViewServiceError extends Error {
  readonly name = 'ViewServiceError';
  constructor(
    readonly code: ViewServiceErrorCode,
    message: string,
  ) {
    super(message);
  }
}
/** One logical create keeps this ID until a definitive outcome, including transport retries. */
export interface ViewCreateContext {
  requestId: string;
  signal?: AbortSignal;
}
/** The current user's authoritative default at the deletion transaction's commit. */
export interface ViewDeleteResult {
  defaultInstance: ViewInstance | null;
}
export interface ViewPermissionSnapshot {
  /** Monotonic authority revision; stale responses cannot restore revoked grants. */
  revision: number;
  instances: Record<string, Required<ViewInstancePermissions>>;
  reorder: boolean;
}
/** Encode one resource ID without allowing URL normalization to change its resource. */
export function encodeViewResourceId(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    value === '.' ||
    value === '..'
  )
    throw new ViewServiceError(
      'INVALID_ARGUMENT',
      '视图资源 ID 不能为空或点路径段',
    );
  try {
    return encodeURIComponent(value);
  } catch {
    throw new ViewServiceError(
      'INVALID_ARGUMENT',
      '视图资源 ID 包含无效 Unicode',
    );
  }
}
