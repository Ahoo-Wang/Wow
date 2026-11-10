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

/**
 * The admission of a definition's code-declared system views: each one's
 * identity, and its config judged by its kind's validator — a board whole,
 * with the analyses it owns judged against the definitions they read. A
 * system view ships with the definition and nobody can overwrite it, so a
 * finding here is one the definition's author has to fix.
 */

import {
  SYSTEM_INSTANCE_ID_SEPARATOR,
  type DashboardViewConfig,
  type Issue,
  type IssuePath,
  type RuntimeLimits,
  type SystemView,
  type ViewDefinition,
} from '../model/index.js';
import { issue, type FieldKindRegistry } from '../filter/index.js';
import { validateRecord } from '../record/index.js';
import { validateAnalysis } from '../analysis/index.js';
import {
  validateDashboard,
  type DefinitionLookup,
} from '../dashboard/index.js';
import { dequal } from 'dequal';
import { ownedRefusals } from './dashboard/owned.js';

import type { ValidateDefinitionOptions } from './validateDefinition.js';

/**
 * A system view ships with the definition and nobody can overwrite it, so one
 * that needs fixing is a read-only view that can never be fixed. Both its
 * identity and its config are checked here.
 */
export function validateSystemViews(
  definition: ViewDefinition,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
  lookup: ValidateDefinitionOptions['definitions'],
): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();

  (definition.views ?? []).forEach((view, index) => {
    const path: IssuePath = ['views', index];
    if (view.id.includes(SYSTEM_INSTANCE_ID_SEPARATOR))
      issues.push(
        issue('definition.view.id-separator', [...path, 'id'], {
          separator: SYSTEM_INSTANCE_ID_SEPARATOR,
        }),
      );
    // Capability lookup goes by id; two views under one id have no answer.
    if (seen.has(view.id))
      issues.push(
        issue('definition.view.id-duplicate', [...path, 'id'], { id: view.id }),
      );
    seen.add(view.id);

    issues.push(
      ...validateSystemConfig(definition, view, path, kinds, limits, lookup),
    );
  });

  return issues;
}

function validateSystemConfig(
  definition: ViewDefinition,
  view: SystemView,
  path: IssuePath,
  kinds: FieldKindRegistry,
  limits: RuntimeLimits,
  lookup: ValidateDefinitionOptions['definitions'],
): Issue[] {
  const kind = view.config.kind;
  const mismatch = issue('definition.view.kind-mismatch', [...path, 'config'], {
    kind,
    definition: definition.kind,
  });

  if (definition.kind === 'dashboard') {
    if (kind !== 'dashboard') return [mismatch];
    // Saved views this layer cannot load, so a store's id is never
    // "unavailable" here: saying so would be a finding about nothing but
    // this layer, and the workbench shows a definition's findings for as
    // long as it lists the board; `ViewEngine` judges it when the board
    // opens. A view declared in code is another matter: every registered
    // definition is at hand (todo C), so one the board names that is not
    // there is said now, at the panel, when the host registers it.
    const board = validateDashboard(
      view.config,
      'system',
      EMPTY_REFERENCES,
      kinds,
      { limits, definitions: lookup && panelDefinitions(lookup) },
    ).filter(found => found.code !== 'dashboard.panel.unavailable');
    return [
      ...under(path, board),
      ...validateOwnedAnalyses(view.config, path, board, {
        kinds,
        limits,
        lookup,
      }),
    ];
  }

  if (kind === 'record')
    return definition.record
      ? under(path, validateRecord(definition, view.config, kinds, { limits }))
      : [mismatch];
  if (kind === 'analysis') {
    if (definition.analysis)
      return under(
        path,
        validateAnalysis(definition, view.config, kinds, { limits }),
      );
    // A `defineView` definition whose snapshot offers no metric leaves its
    // analyses to the source (#3744 review): a source that grants some
    // opens the view, one that grants none cannot — said, never refused.
    return definition.kind === 'data' && definition.described?.open?.analysis
      ? [
          issue(
            'definition.view.analysis-open',
            [...path, 'config'],
            {},
            'warning',
          ),
        ]
      : [mismatch];
  }
  return [mismatch];
}

const EMPTY_REFERENCES = new Map();

/**
 * The analyses a declared board owns, each judged as the analysis it is
 * (`ownedRefusals`): against its own definition when that is registered
 * beside this one, by its shape alone when it is not (the board's own
 * admission says the definition is unknown when it is opened). One that
 * fails is one finding on the panel, and the kernel's own findings under it
 * say why. A panel's finding, so it puts that panel out and leaves the
 * definition usable (`isUsableDefinition`).
 */
function validateOwnedAnalyses(
  config: DashboardViewConfig,
  path: IssuePath,
  said: readonly Issue[],
  {
    kinds,
    limits,
    lookup,
  }: {
    kinds: FieldKindRegistry;
    limits: RuntimeLimits;
    lookup: ValidateDefinitionOptions['definitions'];
  },
): Issue[] {
  const definitionOf = (id: string) => {
    const found = lookup?.(id);
    return found?.kind === 'data' ? found : undefined;
  };
  return ownedRefusals(config, definitionOf, kinds, limits).flatMap(refused => {
    // The board's own admission judged the filter it runs under, merged
    // with the board's: what it already said of the panel is not said twice.
    const errors = refused.errors.filter(
      found =>
        !said.some(
          one =>
            one.path[1] === refused.index &&
            one.code === found.code &&
            dequal(one.params, found.params),
        ),
    );
    return errors.length === 0
      ? []
      : [
          issue(
            'definition.view.owned-invalid',
            [...path, 'config', ...refused.path],
            { panel: refused.panel },
          ),
          ...under(path, errors),
        ];
  });
}

/** The registered definitions as a board's admission reads them. */
function panelDefinitions(
  lookup: (definitionId: string) => ViewDefinition | undefined,
): DefinitionLookup {
  return id => {
    const definition = lookup(id);
    return definition
      ? {
          definition,
          fields: definition.kind === 'data' ? definition.fields : [],
        }
      : null;
  };
}

/** Re-paths a config's findings so they point at the view that holds it. */
function under(path: IssuePath, issues: readonly Issue[]): Issue[] {
  return issues.map(found => ({
    ...found,
    path: [...path, 'config', ...found.path],
  }));
}
