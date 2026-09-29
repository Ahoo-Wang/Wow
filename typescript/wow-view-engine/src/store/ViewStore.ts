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
  ViewAudience,
  ViewConfig,
  ViewInstance,
  ViewInstanceSummary,
  ViewPreferences,
} from '../model/index.js';

/**
 * The only port a backend must satisfy. Its failure type is `ViewStoreError`
 * from `model`, so both sides of the port speak it.
 *
 * Eight methods, and one a store may leave out (`changeAudience`); two
 * consistency rules: a write carries the revision it expects, and a retry
 * reuses its `requestId` so the server can recognise the same intent.
 *
 * Authorization, visibility filtering and deduplication belong to the server.
 * `permissions` only drives which buttons a UI enables.
 */
export interface ViewStore {
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteContext,
  ): Promise<ViewInstance>;
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance>;
  rename(
    id: string,
    title: string,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance>;
  delete(id: string, revision: string, context: WriteContext): Promise<void>;
  getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences>;
  setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteContext,
  ): Promise<ViewPreferences>;
  /**
   * Moves a view to the other audience in place (设为共享／设为个人, D18
   * item 10): the id stays, so a board that shows it keeps showing it.
   *
   * Optional: a store without it has no such entry in the manager, and the
   * engine refuses the command before anything is sent
   * (`view.changeAudience.unsupported`). One that has it keeps the rules of
   * every other instance write — the expected `revision` (`CONFLICT`
   * carrying `instance`), a replayed `requestId` answering the first
   * outcome, a system view refused (`FORBIDDEN`), a missing one
   * `NOT_FOUND` — and two of its own:
   *
   * - **No change is no write.** Asked for the audience the view already
   *   has, it answers the view as it is, revision unmoved (after the
   *   revision check, so a stale one still conflicts).
   * - **A view a shared board shows stays shared.** Made personal, it would
   *   be blank on that board for every other reader, so the store refuses
   *   it as `INVALID`, and **the error's `message` names those boards by
   *   title** — it is what the reader is shown (`view.changeAudience.invalid`
   *   quotes it), so an adapter whose server answers with ids builds the
   *   message from the titles. Only the store sees every board.
   *   Deleting such a view stays allowed (the panel alone breaks).
   */
  changeAudience?(
    id: string,
    audience: ViewAudience,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance>;
  /** Synchronous, because the application fetched it before creating the engine. */
  permissions?(definitionId: string): ViewPermissions;
}

/**
 * One logical write. A retry after a timeout reuses the same `requestId` and
 * the same payload, which is what lets a server deduplicate it.
 */
export interface WriteContext {
  requestId: string;
  signal?: AbortSignal;
}

/** What the current user may do with one definition's views. */
export interface ViewPermissions {
  createPersonal: boolean;
  createShared: boolean;
  reorder: boolean;
  setDefault: boolean;
  instance(id: string): InstancePermissions;
}

export interface InstancePermissions {
  save: boolean;
  rename: boolean;
  delete: boolean;
  /**
   * Whether the view may be moved to the other audience. Absent reads as
   * allowed — silence is not a refusal, as with the rest — and moving it
   * also asks the create permission of the audience it goes to
   * (`createShared` to share it, `createPersonal` to take it back).
   */
  changeAudience?: boolean;
}

/** An empty preference record, which is what an untouched definition has. */
export function emptyPreferences(): ViewPreferences {
  return { order: [], defaultInstanceId: null, revision: '0' };
}
