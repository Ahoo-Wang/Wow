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
import { withCanonicalNames } from './aliases.js';

/**
 * The definition's own references to a field it names by an alias, renamed
 * to the path: its groups, row key and row fields, its aggregation
 * capability and its system views. The fields themselves — a search's
 * fields among them — are renamed as they are narrowed.
 */
function renamedDefinition(
  definition: DataViewDefinition,
  renamed: Readonly<Record<string, string>>,
): DataViewDefinition {
  if (Object.keys(renamed).length === 0) return definition;
  const name = (field: string) => renamed[field] ?? field;
  const within = (scope: string, field: string) => {
    const full = `${scope}.${field}`;
    const to = renamed[full];
    return to === undefined ? field : to.slice(scope.length + 1);
  };
  const next: DataViewDefinition = {
    ...definition,
  };
  if (definition.fieldGroups)
    next.fieldGroups = definition.fieldGroups.map(group => ({
      ...group,
      fields: group.fields.map(name),
    }));
  if (definition.record)
    next.record = {
      ...definition.record,
      rowKey: name(definition.record.rowKey),
      ...(definition.record.rowFields
        ? { rowFields: definition.record.rowFields.map(name) }
        : {}),
    };
  if (definition.analysis)
    next.analysis = {
      ...definition.analysis,
      fields: definition.analysis.fields.map(entry => ({
        ...entry,
        field: name(entry.field),
      })),
      ...(definition.analysis.elements
        ? {
            elements: definition.analysis.elements.map(element => ({
              ...element,
              path: name(element.path),
              aggregations: element.aggregations.map(entry => ({
                ...entry,
                field: within(element.path, entry.field),
              })),
            })),
          }
        : {}),
    };
  if (definition.views)
    next.views = definition.views.map(view =>
      view.config.kind === 'dashboard'
        ? view
        : { ...view, config: withCanonicalNames(view.config, renamed) },
    );
  return next;
}

/** The `fields` of every constraint of one type. */
function constrained(
  descriptor: QueryModelDescriptor,
  type: string,
): string[][] {
  return descriptor.constraints
    .filter(constraint => constraint.type === type && constraint.fields)
    .map(constraint => [...(constraint.fields ?? [])]);
}

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
  const renamed: Record<string, string> = {};
  const fields = narrowFields(definition.fields, {
    renamed,
    descriptor,
    kinds,
    paging: definition.record?.paging,
    emptyIsMissing: new Set(
      constrained(descriptor, 'NULL_OR_EMPTY_AS_MISSING').flat(),
    ),
    findings,
  });
  // What the definition names by an alias it names by the path from here
  // on, so every part of it agrees with its fields (#3519).
  const declared = renamedDefinition(definition, renamed);
  let next: DataViewDefinition = { ...declared, fields };
  if (declared.record) {
    next.record = narrowRecord(declared.record, fields, descriptor, findings);
    // A sort names at most one field of each group (#3515).
    const parallel = constrained(descriptor, 'PARALLEL_ARRAY_SORT');
    if (parallel.length > 0)
      next.record = { ...next.record, parallelArrays: parallel };
  }
  if (declared.analysis) {
    const analysis = narrowAnalysis(declared.analysis, descriptor, findings);
    next = analysis ? { ...next, analysis } : without(next, 'analysis');
  }
  next.narrowing = {
    version: descriptor.version,
    findings,
    ...(Object.keys(renamed).length > 0 ? { renamed } : {}),
  };
  return { definition: next, findings };
}
