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

import { issue } from '../filter/index.js';
import { isViewStoreError, type Issue } from '../model/index.js';
import { isViewCommandError, isViewWriteError } from './write.js';

/**
 * One Issue for whatever a command threw, so a caller renders a single
 * shape. A refusal already carries its Issue; a store failure keeps its code;
 * anything else keeps its message.
 *
 * It lives here rather than in `/react` because the engine itself has to
 * say what a store's failure was — a list that could not be read is an
 * answer with a reason in it, not an exception (see `ViewEngine.list`).
 */
export function toIssue(error: unknown, code: string): Issue {
  if (isViewCommandError(error)) return error.issue;
  if (isViewWriteError(error))
    return error.state.kind === 'rejected'
      ? error.state.issue
      : issue(`${code}.${error.state.kind}`, []);
  if (isViewStoreError(error))
    return issue(`${code}.${error.code.toLowerCase()}`, [], {
      reason: error.message,
    });
  return issue(code, [], {
    reason: error instanceof Error ? error.message : String(error),
  });
}
