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

import type { ViewConfig } from './config.js';

/**
 * Who configured a view, who sees it and who may change it.
 *
 * - `system`: developers or operators; read-only for everyone else.
 * - `shared`: a user with permission; visible across the definition.
 * - `personal`: its owner only.
 */
export type ViewScope = 'system' | 'shared' | 'personal';

export const VIEW_SCOPES: readonly ViewScope[] = [
  'system',
  'shared',
  'personal',
];

/** A saved config plus its identity. Only configs persist, never results. */
export interface ViewInstance {
  id: string;
  definitionId: string;
  title: string;
  scope: ViewScope;
  /** Opaque; compared for equality only. Code-declared system views use `code`. */
  revision: string;
  config: ViewConfig;
}

/** Revision of a system view that ships with the definition. */
export const CODE_REVISION = 'code';

/** What a list returns: enough to render the sidebar, without the config. */
export type ViewInstanceSummary = Omit<ViewInstance, 'config'>;

/** One user's ordering and default view for one definition. */
export interface ViewPreferences {
  /** Explicitly ordered instance ids; unlisted ones follow in server order. */
  order: string[];
  defaultInstanceId: string | null;
  revision: string;
}
