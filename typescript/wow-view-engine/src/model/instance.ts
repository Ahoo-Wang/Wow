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

import type { ViewConfig, ViewKind } from './config.js';

/**
 * Who configured a view, who sees it and who may change it.
 *
 * - `system`: developers or operators; read-only for everyone else.
 * - `shared`: a user with permission; visible across the definition.
 * - `personal`: its owner only.
 *
 * These are the legal combinations of two facts — who a view is for, and
 * whether it came from a user — rather than one dimension. A system view is
 * always a shared view, and a personal system view is not a thing; holding
 * the pair as a single value is what makes that last sentence impossible to
 * write down instead of a rule somebody has to enforce. `audienceOf` and
 * `isSystemScope` read the two facts back out, and every caller asks through
 * them rather than comparing the value itself — a `scope !== 'personal'`
 * spelled out at the point of use is this rule, restated where nobody can
 * see it is the same rule.
 */
export type ViewScope = 'system' | 'shared' | 'personal';

export const VIEW_SCOPES: readonly ViewScope[] = [
  'system',
  'shared',
  'personal',
];

/** Who a view is for. A system view is a shared view; see `ViewScope`. */
export type ViewAudience = 'personal' | 'shared';

/**
 * The two, in the order every list of views shows them in: personal above
 * shared. The sidebar, the switcher's menu and the manager's dialog are three
 * views of one list, so the order is the model's rather than each of theirs.
 */
export const VIEW_AUDIENCES: readonly ViewAudience[] = ['personal', 'shared'];

/** The audience a scope puts a view in: `system` answers `shared`. */
export function audienceOf(scope: ViewScope): ViewAudience {
  return scope === 'personal' ? 'personal' : 'shared';
}

/** Whether the view was configured for everyone rather than by a user. */
export function isSystemScope(scope: ViewScope): boolean {
  return scope === 'system';
}

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

/**
 * What a list returns: enough to render the sidebar, without the config.
 *
 * It is not "an instance minus its config": `kind` is the config's own tag,
 * projected, because one data definition holds record and analysis views
 * together and a list has to tell them apart. A store answering `list` reads
 * it off the config it stores — it never records a second copy, which could
 * then disagree with the config it names.
 */
export interface ViewInstanceSummary {
  id: string;
  definitionId: string;
  title: string;
  scope: ViewScope;
  /** Always equal to the `kind` of the config this summary names. */
  kind: ViewKind;
  revision: string;
}

/** What a list shows: an instance without the config it holds. */
export function toSummary(instance: ViewInstance): ViewInstanceSummary {
  const { id, definitionId, title, scope, revision, config } = instance;
  return { id, definitionId, title, scope, kind: config.kind, revision };
}

/** One user's ordering and default view for one definition. */
export interface ViewPreferences {
  /** Explicitly ordered instance ids; unlisted ones follow in server order. */
  order: string[];
  defaultInstanceId: string | null;
  revision: string;
}
