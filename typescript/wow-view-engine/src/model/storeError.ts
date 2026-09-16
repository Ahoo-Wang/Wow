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

import type { ViewInstance, ViewPreferences } from './instance.js';

export type ViewStoreErrorCode =
  /** The expected revision no longer matches. */
  | 'CONFLICT'
  | 'NOT_FOUND'
  | 'FORBIDDEN'
  /** Reached but refused; the payload is wrong. */
  | 'INVALID'
  /** Not reached, or reached with an unknown outcome. */
  | 'UNAVAILABLE';

export const VIEW_STORE_ERROR_CODES: readonly ViewStoreErrorCode[] = [
  'CONFLICT',
  'NOT_FOUND',
  'FORBIDDEN',
  'INVALID',
  'UNAVAILABLE',
];

/**
 * The single failure type of the persistence port. A backend adapter maps its
 * transport errors, HTTP status codes included, onto these five codes.
 *
 * It lives in `model` rather than next to the port because both sides of the
 * port speak it: a store raises it, and the runtime classifies a write outcome
 * by it without depending on any store implementation.
 */
export class ViewStoreError extends Error {
  readonly code: ViewStoreErrorCode;
  /** The state the server holds, when a conflict can report it. */
  readonly remote?: ViewInstance | ViewPreferences;

  constructor(
    code: ViewStoreErrorCode,
    message: string,
    remote?: ViewInstance | ViewPreferences,
  ) {
    super(message);
    this.name = 'ViewStoreError';
    this.code = code;
    this.remote = remote;
  }
}

/**
 * Structural, so an error raised by a second copy of this package, or by a
 * store that builds the shape itself, is still recognised.
 */
export function isViewStoreError(error: unknown): error is ViewStoreError {
  if (typeof error !== 'object' || error === null) return false;
  const code = (error as { code?: unknown }).code;
  return (
    typeof code === 'string' &&
    (VIEW_STORE_ERROR_CODES as readonly string[]).includes(code)
  );
}
