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

import type { QueryModelDescriptor } from '@ahoo-wang/wow-client';
import {
  without,
  type DataViewDefinition,
  type Issue,
} from '../model/index.js';
import type { FieldKindRegistry } from '../filter/index.js';
import { narrowAnalysis } from './analysis.js';
import { narrowFields } from './fields.js';
import { narrowRecord } from './record.js';

/** A definition as one deployment admits it, and what was taken away. */
export interface NarrowedDefinition {
  definition: DataViewDefinition;
  /**
   * `warning` for a capability the deployment lacks, `note` for one it
   * offers another way, `error` where the deployment contradicts the
   * definition and a view of it would send queries bound to be refused or
   * misread (capabilities.md 4.6).
   */
  findings: Issue[];
}

/**
 * The definition narrowed to what the source's descriptor admits: the
 * capabilities in force are the ones the definition declares **and** the
 * descriptor lists (capabilities.md 2, 4). The definition only narrows —
 * what the descriptor offers beyond it is never added, because which
 * fields a reader sees, under which names, is the definition's to say.
 *
 * The result is an ordinary definition, so the kernels, the controllers and
 * the default UI read it as they read any other and never learn a
 * descriptor exists. This is the one place that reads one.
 */
export function narrowDefinition(
  definition: DataViewDefinition,
  descriptor: QueryModelDescriptor,
  kinds: FieldKindRegistry,
): NarrowedDefinition {
  const findings: Issue[] = [];
  const fields = narrowFields(definition.fields, {
    descriptor,
    kinds,
    paging: definition.record?.paging,
    findings,
  });
  let next: DataViewDefinition = { ...definition, fields };
  if (definition.record)
    next.record = narrowRecord(definition.record, fields, descriptor, findings);
  if (definition.analysis) {
    const analysis = narrowAnalysis(definition.analysis, descriptor, findings);
    next = analysis ? { ...next, analysis } : without(next, 'analysis');
  }
  next.narrowing = { version: descriptor.version, findings };
  return { definition: next, findings };
}
