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
  ViewDefinition,
  ViewInstance,
  ViewInstanceList,
  ViewInstancePermissions,
  ViewSource,
  ViewCreateInput,
} from './viewModel.js';
import type {
  ViewCreateContext,
  ViewDeleteResult,
  ViewPermissionSnapshot,
} from './viewServiceContract.js';
/** Definition metadata, independent of instance persistence and runtime sources. */
export interface ViewDefinitionService {
  load?(definitionId: string, signal?: AbortSignal): Promise<ViewDefinition>;
}
/** Saved view content. The service enforces ownership, permissions and revisions. */
export interface ViewInstanceService {
  list?(definitionId: string, signal?: AbortSignal): Promise<ViewInstanceList>;
  load?(instanceId: string, signal?: AbortSignal): Promise<ViewInstance>;
  create?(
    instance: ViewCreateInput,
    context: ViewCreateContext,
  ): Promise<ViewInstance>;
  save?(instance: ViewInstance): Promise<ViewInstance>;
  delete?(instanceId: string, revision: string): Promise<ViewDeleteResult>;
  rename?(
    instanceId: string,
    title: string,
    revision: string,
  ): Promise<ViewInstance>;
}
/** Current user's display preferences; never changes shared view content. */
export interface ViewPreferenceService {
  saveOrder?(definitionId: string, instanceIds: string[]): Promise<void>;
  saveDefault?(definitionId: string, instanceId: string | null): Promise<void>;
}
/** Synchronous UI policy projection plus explicit refresh and change notifications. */
export interface ViewPermissionService {
  getInstance?(instance: ViewInstance): ViewInstancePermissions;
  getDefinition?(): Pick<
    ViewPermissionSnapshot,
    'reorder' | 'createPersonal' | 'createShared'
  >;
  /** Initialize this service's synchronous getters before resolving; awaited by engine.load(). */
  load?(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPermissionSnapshot>;
  /** Also used for initialization when load is absent. Publish subsequent changes via subscribe. */
  refresh?(signal?: AbortSignal): Promise<void>;
  subscribe?(listener: () => void): () => void;
}
export interface DashboardCandidate {
  id: string;
  definitionId: string;
  title: string;
  kind: 'record' | 'analysis';
}
export interface DashboardHost {
  search?(
    input: { query: string; cursor?: string },
    signal?: AbortSignal,
  ): Promise<{ items: DashboardCandidate[]; nextCursor: string | null }>;
  openOriginal?(reference: { instanceId: string; definitionId: string }): void;
}
/** Composition facade within one fixed access scope; each service is independently replaceable. */
export interface ViewHost {
  dashboard?: DashboardHost;
  definition?: ViewDefinitionService;
  instance?: ViewInstanceService;
  preference?: ViewPreferenceService;
  permission?: ViewPermissionService;
  /** Local runtime bridge. This is not a view-service REST operation. */
  resolveSource(sourceId: string): ViewSource | Promise<ViewSource>;
}
