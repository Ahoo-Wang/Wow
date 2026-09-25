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

/*
 * What a query hook shows, and how each event changes it: section 3 of
 * docs/design/architecture.md as pure functions, with no React. The
 * request itself (aborting it, ignoring a late answer) is useQueryRunner's.
 */

import type { QueryStatus } from '../types.js';

/** What a query hook shows at one moment. */
export interface QueryState<R, E> {
  status: QueryStatus;
  result: R | undefined;
  error: E | undefined;
}

/** Something that happens to a query hook. */
export type QueryEvent<R, E> =
  | { type: 'start' }
  | { type: 'succeed'; result: R | undefined }
  | { type: 'fail'; error: E }
  | { type: 'abort' }
  | { type: 'reset' };

/**
 * The first state. A hook that will run its query as soon as it mounts
 * starts `loading`, so the server and the first client frame already show
 * what the next frames show; any other starts `idle`.
 */
export function initialQueryState<R, E>(
  runsOnMount: boolean,
): QueryState<R, E> {
  return {
    status: runsOnMount ? 'loading' : 'idle',
    result: undefined,
    error: undefined,
  };
}

/**
 * The state after one event:
 *
 * - `start`: `loading`; the last result stays until the new one arrives, the
 *   error goes.
 * - `succeed`: `success` with the new result (`undefined` for a hook that
 *   keeps its own, as the list-stream hooks keep `items`).
 * - `fail`: `error`; the last result stays, so a failed refresh does not
 *   blank what was shown.
 * - `abort`: `idle`; the last result stays, the error goes.
 * - `reset`: `idle`, with neither result nor error.
 */
export function queryTransition<R, E>(
  state: QueryState<R, E>,
  event: QueryEvent<R, E>,
): QueryState<R, E> {
  switch (event.type) {
    case 'start':
      return { status: 'loading', result: state.result, error: undefined };
    case 'succeed':
      return { status: 'success', result: event.result, error: undefined };
    case 'fail':
      return { status: 'error', result: state.result, error: event.error };
    case 'abort':
      return { status: 'idle', result: state.result, error: undefined };
    case 'reset':
      return { status: 'idle', result: undefined, error: undefined };
  }
}
