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
import { sourceReason } from './sourceReason.js';
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
  return commandIssue(error, code) ?? messageIssue(error, code);
}

/**
 * `toIssue` for a failure that may have come from a source: a query or an
 * export over the host's data. What the engine's own commands and stores
 * say is read as `toIssue` reads it; anything else is asked for the
 * source's own reason (`sourceReason`), which may take reading a body.
 */
export async function sourceIssue(
  error: unknown,
  code: string,
): Promise<Issue> {
  return (
    commandIssue(error, code) ??
    issue(code, [], { reason: await sourceReason(error) })
  );
}

/** The issue an engine command or store failure already names, if it is one. */
function commandIssue(error: unknown, code: string): Issue | null {
  if (isViewCommandError(error)) return error.issue;
  if (isViewWriteError(error))
    return error.state.kind === 'rejected'
      ? error.state.issue
      : issue(`${code}.${error.state.kind}`, []);
  if (isViewStoreError(error))
    return issue(`${code}.${error.code.toLowerCase()}`, [], {
      reason: error.message,
    });
  return null;
}

function messageIssue(error: unknown, code: string): Issue {
  return issue(code, [], {
    reason: error instanceof Error ? error.message : String(error),
  });
}

/**
 * Why one whole record could not be read (`fetchRecord`): refused to this
 * reader — the source answered 401 or 403 — or failed, in the source's own
 * words either way. A refusal is its own sentence because it is the one a
 * reader cannot fix by trying again: a link to a record of another tenant
 * says so, rather than "could not be read".
 */
export async function recordReadIssue(error: unknown): Promise<Issue> {
  return sourceIssue(
    error,
    refused(error) ? 'record.detail.forbidden' : 'record.detail.failed',
  );
}

/** Whether a source's rejection is an HTTP refusal of the reader. */
function refused(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('exchange' in error))
    return false;
  const { exchange } = error as { exchange?: { response?: unknown } };
  const response = exchange?.response;
  const status =
    typeof response === 'object' && response !== null && 'status' in response
      ? response.status
      : undefined;
  return status === 401 || status === 403;
}
