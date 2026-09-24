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
  ViewInstance,
  ViewInstanceSummary,
  ViewPreferences,
} from '../model/index.js';

/**
 * The only port a backend must satisfy. Its failure type is `ViewStoreError`
 * from `model`, so both sides of the port speak it.
 *
 * Eight methods, two consistency rules: a write carries the revision it
 * expects, and a retry reuses its `requestId` so the server can recognise the
 * same intent.
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
}

/** An empty preference record, which is what an untouched definition has. */
export function emptyPreferences(): ViewPreferences {
  return { order: [], defaultInstanceId: null, revision: '0' };
}
