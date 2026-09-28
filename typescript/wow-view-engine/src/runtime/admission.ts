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
  type TextResolver,
  type ViewDefinition,
} from '../model/index.js';
import {
  builtinFieldKinds,
  issue,
  type FieldKindRegistry,
} from '../filter/index.js';
import { narrowDefinition } from '../capabilities/index.js';
import { sayDefinition } from './definitions.js';
import { validateDefinition } from './validateDefinition.js';

/**
 * What `admit` takes one of: a definition, or a resource holding one — the
 * shape a host registers (host-integration.md 4), so a host's list of
 * resources is passed as it is.
 */
export type Admissible = ViewDefinition | { definition: ViewDefinition };

export interface AdmitOptions {
  /** The words the definitions' keys are said in (`ViewEngineOptions.text`). */
  text?: TextResolver;
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
 * definition (C) — and each data definition narrowed to its descriptor as
 * a source would narrow it (N5), what that takes away said too. A
 * definition over a source with no descriptor given is said to be, since
 * its capabilities go unchecked.
 */
export function admit(
  definitions: readonly Admissible[],
  descriptors: Readonly<Record<string, QueryModelDescriptor>>,
  options: AdmitOptions = {},
): AdmitFinding[] {
  const kinds = options.kinds ?? builtinFieldKinds;
  const limits = { ...DEFAULT_RUNTIME_LIMITS, ...options.limits };
  const said = definitions.map(entry =>
    sayDefinition(
      'definition' in entry ? entry.definition : entry,
      options.text,
    ),
  );
  const findings = new Map<string, Issue[]>();
  const effective = new Map<string, ViewDefinition>();
  for (const { definition, findings: text } of said) {
    findings.set(definition.id, [...text]);
    effective.set(definition.id, definition);
    if (definition.kind !== 'data') continue;
    const descriptor = descriptors[definition.source];
    if (!descriptor) {
      findings
        .get(definition.id)
        ?.push(
          issue(
            'definition.descriptor.missing',
            ['source'],
            { source: definition.source },
            'warning',
          ),
        );
      continue;
    }
    const narrowed = narrowDefinition(definition, descriptor, kinds);
    findings.get(definition.id)?.push(...narrowed.findings);
    effective.set(definition.id, narrowed.definition);
  }
  return said.flatMap(({ definition }) =>
    [
      ...(findings.get(definition.id) ?? []),
      ...validateDefinition(definition, kinds, {
        limits,
        definitions: id => effective.get(id),
      }),
    ].map(found => ({ ...found, definition: definition.id })),
  );
}
