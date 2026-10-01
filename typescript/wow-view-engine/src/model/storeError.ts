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
  /** The instance the server holds, when an instance write conflicted. */
  readonly instance?: ViewInstance;
  /** The preferences it holds, when a preference write conflicted. */
  readonly preferences?: ViewPreferences;
  /**
   * With `UNAVAILABLE`: the browser's own storage would not keep the write
   * (full, or turned off), so it never left the device and nothing was
   * written. The outcome is known, not lost on the way, and the reader is
   * told what to free rather than that the result never came back.
   */
  readonly storage?: true;
  /**
   * With `INVALID` from `changeAudience`: the shared dashboards that show
   * the view and so keep it shared, by **title** as stored — a title
   * written as a key stays one, said where it is shown (D2). The engine
   * says the refusal in its own words around them
   * (`view.changeAudience.invalid.shared-boards`); `message` stays the
   * store's own, for logs.
   */
  readonly boards?: readonly string[];

  constructor(
    code: ViewStoreErrorCode,
    message: string,
    held: ConflictingState & {
      storage?: true;
      boards?: readonly string[];
    } = {},
  ) {
    super(message);
    this.name = 'ViewStoreError';
    this.code = code;
    this.instance = held.instance;
    this.preferences = held.preferences;
    if (held.storage) this.storage = true;
    if (held.boards) this.boards = held.boards;
  }
}

/**
 * What the server holds, said by which of the two it is.
 *
 * One member carrying either used to be enough to compile, and so the two
 * were told apart by casting at each point of use: a preference conflict
 * that arrived carrying an instance was read as preferences all the way to
 * the screen. A store fills in the one its own write was about, and nothing
 * downstream has to guess.
 */
export interface ConflictingState {
  instance?: ViewInstance;
  preferences?: ViewPreferences;
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
