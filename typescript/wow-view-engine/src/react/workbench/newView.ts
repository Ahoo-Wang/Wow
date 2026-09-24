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
import { defaultAnalysisConfig } from '../../analysis/index.js';
import { emptyDashboardConfig } from '../../dashboard/index.js';
import type {
  RuntimeLimits,
  ViewAudience,
  ViewConfig,
  ViewDefinition,
  ViewKind,
} from '../../model/index.js';
import { defaultRecordConfig } from '../../record/index.js';
import type { ViewPermissions } from '../../store/index.js';

/**
 * What a workbench needs before it can make a view from nothing.
 *
 * The title is required because the engine refuses a view without one, and
 * it is the UI's to word — this layer carries no catalogue — so a workbench
 * that gives none simply offers no new view. A template is the host's own
 * first view of one kind; a kind without one starts from the default built
 * from the definition.
 */
export interface NewViewOptions {
  /** The name a new view opens under, until the first save names it. */
  title: string;
  /**
   * What a new view of each kind starts from; the kind's own default when
   * its entry is left out. A template of the wrong kind is refused as no
   * template: it is a mistake at the call site, and a workbench that then
   * offered the default instead would hide it.
   */
  templates?: Partial<Record<ViewKind, ViewConfig>>;
}

/** What `create` will hand the engine, or null where no view can be made. */
export interface Blank {
  scope: ViewAudience;
  config: ViewConfig;
}

/**
 * Whether the definition offers views of this kind at all: a data definition
 * with no analysis capability has no analysis view to make, and a dashboard
 * definition holds dashboards alone.
 */
function offers(definition: ViewDefinition, kind: ViewKind): boolean {
  if (kind === 'dashboard') return definition.kind === 'dashboard';
  if (definition.kind !== 'data') return false;
  return kind === 'record'
    ? definition.record !== undefined
    : definition.analysis !== undefined;
}

/** A complete starting config for a view of this kind on this definition. */
function blankConfig(
  definition: ViewDefinition,
  kind: ViewKind,
  limits: RuntimeLimits,
): ViewConfig {
  if (kind === 'dashboard' || definition.kind !== 'data')
    return emptyDashboardConfig();
  return kind === 'record'
    ? defaultRecordConfig(definition, limits)
    : defaultAnalysisConfig(definition, limits);
}

/**
 * Where a new view goes: the user's own, unless they may only publish. The
 * first save asks again, with both audiences on offer; this is only where
 * the view sits while it is being made.
 */
function newViewScope(permissions: ViewPermissions): ViewAudience | null {
  if (permissions.createPersonal) return 'personal';
  if (permissions.createShared) return 'shared';
  return null;
}

/**
 * The view `create` would make, decided once from the definition, the
 * permissions and the host's template — or null, which is what turns every
 * "new view" control off: a definition that has no such kind, a user who
 * may create nowhere, or a workbench that gave no title to open one under.
 */
export function blankView(
  definition: ViewDefinition | undefined,
  kind: ViewKind,
  permissions: ViewPermissions,
  limits: RuntimeLimits,
  newView: NewViewOptions | undefined,
): Blank | null {
  if (!newView || !definition || !offers(definition, kind)) return null;
  const scope = newViewScope(permissions);
  if (scope === null) return null;
  const config =
    newView.templates?.[kind] ?? blankConfig(definition, kind, limits);
  return config.kind === kind ? { scope, config } : null;
}
