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
  AggregationFieldCapability,
  AnalysisCapability,
  AnalysisViewConfig,
  DataViewDefinition,
  FieldDefinition,
} from '../model/index.js';

/**
 * What an analysis may reach, once the configured element paths are expanded.
 *
 * Element fields are addressed as `path.field`, so a name shared with a root
 * field still resolves to exactly one field and one capability.
 */
export interface AnalysisScope {
  fields: Map<string, FieldDefinition>;
  aggregations: Map<string, AggregationFieldCapability>;
  /** Element paths the capability declares, whether configured or not. */
  declaredPaths: Set<string>;
}

export function analysisScope(
  definition: DataViewDefinition,
  capability: AnalysisCapability,
  config?: Pick<AnalysisViewConfig, 'elements'>,
): AnalysisScope {
  const fields = new Map(definition.fields.map(field => [field.name, field]));
  const aggregations = new Map(
    capability.fields.map(entry => [entry.field, entry]),
  );
  const declaredPaths = new Set(
    (capability.elements ?? []).map(element => element.path),
  );

  for (const element of capability.elements ?? []) {
    const configured = (config?.elements ?? []).some(
      entry => entry.path === element.path,
    );
    if (!configured) continue;
    for (const field of element.fields)
      fields.set(qualify(element.path, field.name), {
        ...field,
        name: qualify(element.path, field.name),
      });
    for (const entry of element.aggregations)
      aggregations.set(qualify(element.path, entry.field), {
        ...entry,
        field: qualify(element.path, entry.field),
      });
  }

  return { fields, aggregations, declaredPaths };
}

/** Element fields are written with their path, which keeps them unambiguous. */
export function qualify(path: string, field: string): string {
  return field.startsWith(`${path}.`) ? field : `${path}.${field}`;
}

export function analysisCapabilityOf(
  definition: DataViewDefinition,
): AnalysisCapability | undefined {
  return definition.analysis;
}
