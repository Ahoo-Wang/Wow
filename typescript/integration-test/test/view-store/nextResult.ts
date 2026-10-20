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
  ViewConfig,
  ViewRuntime,
  ViewRuntimeState,
} from '@ahoo-wang/wow-view-engine';

/**
 * The next answer a runtime settles on after `previous`: resolves with the
 * state once a result other than `previous` has landed, and rejects with the
 * query's issue when it fails instead.
 */
export function nextResult<C extends ViewConfig>(
  runtime: ViewRuntime<C>,
  previous: ViewRuntimeState<C>['result'] = null,
): Promise<ViewRuntimeState<C>> {
  return new Promise((resolve, reject) => {
    let stop = () => {};
    const check = () => {
      const state = runtime.getSnapshot();
      const refused = state.issues.filter(found => found.severity === 'error');
      if (state.query.status === 'error') {
        stop();
        reject(new QueryFailed(state.query.error));
      } else if (state.query.status === 'idle' && refused.length > 0) {
        // Admission refused the draft, so nothing will run.
        stop();
        reject(new QueryFailed(refused));
      } else if (
        state.query.status === 'success' &&
        state.result !== null &&
        state.result !== previous
      ) {
        stop();
        resolve(state);
      }
    };
    stop = runtime.subscribe(check);
    check();
  });
}

/** What `nextResult` rejects with: the issue the runtime reported. */
export class QueryFailed extends Error {
  constructor(readonly issue: unknown) {
    super(`The query failed: ${JSON.stringify(issue)}`);
    this.name = 'QueryFailed';
  }
}
