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
  Issue,
  ViewConfig,
  ViewInstance,
  ViewPreferences,
} from '../model/index.js';

/**
 * The body of a write, kept as it was sent. A retry and an overwrite replay
 * this, not the current draft, so the user's intent survives whatever they
 * edited while the request was in flight.
 *
 * The expected `revision` travels with it for the same reason: a retry is the
 * same logical write, so it carries the same expectation, and only an
 * overwrite moves it forward to the revision the conflict reported.
 */
export type WritePayload =
  | { action: 'preferences'; definitionId: string; next: ViewPreferences }
  | {
      action: 'create';
      input: Omit<ViewInstance, 'id' | 'revision'>;
      /** `first-save` binds the source runtime to the new instance; `save-as` leaves it alone. */
      intent: 'first-save' | 'save-as';
    }
  | { action: 'save'; id: string; revision: string; config: ViewConfig }
  | { action: 'rename'; id: string; revision: string; title: string }
  | { action: 'delete'; id: string; revision: string };

export type WriteAction = WritePayload['action'];

/**
 * An outcome that is not success. A successful write advances the baseline and
 * clears this field instead.
 *
 * `unknown` is the one that matters: the request left, and nothing came back.
 * It is neither a failure nor a success, and the only safe move is to replay
 * the same `requestId` and let the server deduplicate.
 */
export type WriteState = { requestId: string; payload: WritePayload } & (
  | { kind: 'conflict'; remote: ViewInstance | ViewPreferences }
  | { kind: 'rejected'; issue: Issue }
  | { kind: 'unknown' }
);

/**
 * Addresses a write that no open runtime owns, such as renaming a view from
 * the list. Its outcome lives in `engine.pendingWrites()`.
 */
export interface WriteHandle {
  readonly id: string;
}

/**
 * How every write command rejects. It carries the outcome and its handle, so
 * a caller acts on its own command instead of guessing which entry of
 * `pendingWrites()` belongs to it.
 */
export class ViewWriteError extends Error {
  readonly handle: WriteHandle;
  readonly state: WriteState;

  constructor(state: WriteState) {
    super(`View write ${state.payload.action} ended as ${state.kind}`);
    this.name = 'ViewWriteError';
    this.state = state;
    this.handle = { id: state.requestId };
  }
}

export function isViewWriteError(error: unknown): error is ViewWriteError {
  return error instanceof ViewWriteError;
}

/**
 * A command the engine refused before dispatching it: a missing definition, an
 * empty title, a permission the user does not hold, a config that still has an
 * error. Nothing was sent, so there is no outcome to retry.
 */
export class ViewCommandError extends Error {
  readonly issue: Issue;

  constructor(issue: Issue) {
    super(`View command refused: ${issue.code}`);
    this.name = 'ViewCommandError';
    this.issue = issue;
  }
}

export function isViewCommandError(error: unknown): error is ViewCommandError {
  return error instanceof ViewCommandError;
}
