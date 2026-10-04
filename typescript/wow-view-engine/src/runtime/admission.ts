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
 * Everything a host declares, admitted as the engine would admit it, in a
 * test (`/testing`'s `admit`, host-integration.md 6): no store, no source,
 * no clock — the descriptors are the snapshots the host commits.
 */

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  type Issue,
  type RuntimeLimits,
  type ViewDefinition,
} from '../model/index.js';
import {
  builtinFieldKinds,
  issue,
  type FieldKindRegistry,
} from '../filter/index.js';
import { narrowDefinition } from '../capabilities/index.js';
import { sayDefinition } from './definitions.js';
import type { ViewSource } from './source.js';
import { validateDefinition } from './validateDefinition.js';

/**
 * What `admit` takes one of: a definition, or a resource holding one — the
 * shape a host registers (host-integration.md 4), so a host's list of
 * resources is passed as it is, sources and all.
 */
export type Admissible =
  ViewDefinition | { definition: ViewDefinition; source?: ViewSource };

export interface AdmitOptions {
  /** The words the definitions' keys are said in (`ViewEngineOptions.text`). */
  text?(key: string): string | undefined;
  kinds?: FieldKindRegistry;
  limits?: Partial<RuntimeLimits>;
}

/** One finding, with the definition it is about. */
export type AdmitFinding = Issue & { definition: string };

/**
 * Every finding the engine would have about `definitions` over
 * `descriptors` (the snapshots, by `DataViewDefinition.source`), each with
 * the definition it is about; `[]` for a host's declarations that hold.
 *
 * Each definition is admitted as the engine admits it when registered —
 * its keys said in `text`, its own rules, its boards against every other
 * definition (C), each judged as declared, keys and all — and each data
 * definition narrowed to its descriptor as a source would narrow it (N5),
 * what that takes away said too. Given a full resource list — at least
 * one resource carrying its `source` — a data definition passed as a
 * resource is held to the engine's start-up check as well: a source
 * registered under its `source` key by one of the resources passed, or
 * `definition.source.unregistered`. A list of `{ definition }` alone says
 * nothing of sources and is not checked for them. A definition over a
 * source with no descriptor given is said to be, since its capabilities go
 * unchecked.
 */
export function admit(
  definitions: readonly Admissible[],
  descriptors: Readonly<Record<string, QueryModelDescriptor>>,
  options: AdmitOptions = {},
): AdmitFinding[] {
  const kinds = options.kinds ?? builtinFieldKinds;
  const limits = { ...DEFAULT_RUNTIME_LIMITS, ...options.limits };
  // Called on the host's object, as the engine's registry calls it.
  const text = (key: string) => options.text?.(key);
  const entries = definitions.map(entry =>
    'definition' in entry
      ? { definition: entry.definition, resource: true }
      : { definition: entry, resource: false },
  );
  // The source keys the resources register, as the engine reads them.
  const sources = new Set(
    definitions.flatMap(entry =>
      'definition' in entry && entry.source && entry.definition.kind === 'data'
        ? [entry.definition.source]
        : [],
    ),
  );
  // Only a list that names sources at all is checked for them: a list of
  // `{ definition }` alone, as 9.2.1 took it, says nothing of where rows
  // come from, and stays as it was.
  const checksSources = sources.size > 0;
  // Judged as declared, as the engine's registry judges it: the keys are
  // said only to find the ones `text` has no words for.
  const declared = new Map(
    entries.map(({ definition }) => [definition.id, definition]),
  );
  return entries.flatMap(({ definition, resource }) =>
    [
      ...sayDefinition(definition, text).findings,
      ...(checksSources &&
      resource &&
      definition.kind === 'data' &&
      !sources.has(definition.source)
        ? [
            issue('definition.source.unregistered', ['source'], {
              source: definition.source,
            }),
          ]
        : []),
      ...narrowed(definition, descriptors, kinds),
      // Judged as the engine's registry judges it — against the definitions
      // as declared — so a host's test says what the application's start
      // says (`onIssue`). The narrowing's own findings are above.
      ...validateDefinition(definition, kinds, {
        limits,
        definitions: id => declared.get(id),
      }),
    ].map(found => ({ ...found, definition: definition.id })),
  );
}

/**
 * What narrowing a data definition to its source's descriptor finds, or
 * that no descriptor was given for it.
 */
function narrowed(
  definition: ViewDefinition,
  descriptors: Readonly<Record<string, QueryModelDescriptor>>,
  kinds: FieldKindRegistry,
): Issue[] {
  if (definition.kind !== 'data') return [];
  const descriptor = descriptors[definition.source];
  if (!descriptor)
    return [
      issue(
        'definition.descriptor.missing',
        ['source'],
        { source: definition.source },
        'warning',
      ),
    ];
  return narrowDefinition(definition, descriptor, kinds).findings;
}
