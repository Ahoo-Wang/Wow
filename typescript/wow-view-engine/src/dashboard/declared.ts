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
 * A board's references to views declared in code, read off the registered
 * definitions (todo C).
 *
 * A view a definition declares has an id made of the two (`system:def:view`,
 * `systemInstanceId`), so whether a board names one that is there needs no
 * store: the definitions admission is handed (`ValidateDashboardOptions
 * .definitions`, the same lookup an owned view is judged by) answer it at
 * once — where the definitions are registered, before any board opens, and
 * again as a board opens, before its references have loaded. A saved view's
 * id is a store's, and is only known once read.
 *
 * Every question here goes through that one lookup, over whatever the host
 * registered, and none through how it was registered.
 */

import {
  CODE_REVISION,
  parseSystemInstanceId,
  systemInstanceId,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type { PanelDefinition, PanelReference } from './validate.js';

/** How a board's admission reads a definition, by id. */
export type DefinitionLookup = (definitionId: string) => PanelDefinition | null;

/**
 * What a view id names among the views declared in code:
 * - `undefined` — not such an id (a store's), or nothing to look it up in;
 * - `{ reference }` — a view a registered definition declares, read as a
 *   saved one would be;
 * - `{ missing }` — no registered definition declares it, and the title of
 *   the definition it names when that one is registered, so a reader is told
 *   where the view was meant to be, never its id.
 */
export type Declared =
  | { reference: PanelReference }
  | { missing: { definition: string | null } }
  | undefined;

export function declaredView(
  instanceId: unknown,
  lookup: DefinitionLookup | undefined,
): Declared {
  if (typeof instanceId !== 'string' || !lookup) return undefined;
  const named = parseSystemInstanceId(instanceId);
  if (!named) return undefined;
  const found = lookup(named.definitionId);
  if (!found) return { missing: { definition: null } };
  const view = found.definition.views?.find(one => one.id === named.viewId);
  if (!view) return { missing: { definition: found.definition.title } };
  return {
    reference: {
      ...found,
      instance: {
        id: systemInstanceId(named.definitionId, named.viewId),
        definitionId: named.definitionId,
        title: view.title,
        scope: 'system',
        revision: CODE_REVISION,
        config: view.config,
      },
    },
  };
}

/**
 * A declared view a panel shows, missing: an error at the panel — it cannot
 * run — naming where the view was meant to be.
 */
export function missingView(
  missing: { definition: string | null },
  at: IssuePath,
): Issue {
  return missing.definition === null
    ? issue('dashboard.panel.view-unknown', at)
    : issue('dashboard.panel.view-undeclared', at, {
        definition: missing.definition,
      });
}
