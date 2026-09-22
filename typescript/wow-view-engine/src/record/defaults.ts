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

import {
  isFieldlessKind,
  DEFAULT_RUNTIME_LIMITS,
  type DataViewDefinition,
  type RecordCapability,
  type RecordViewConfig,
  type RuntimeLimits,
  type ViewDefinition,
} from '../model/index.js';
import { emptyFilter } from '../filter/index.js';

export function recordCapabilityOf(
  definition: ViewDefinition,
): RecordCapability | undefined {
  return definition.kind === 'data' ? definition.record : undefined;
}

/**
 * A complete starting config, because `create` takes a config rather than
 * inventing one: a definition that `validateDefinition` accepted always has a
 * layout and a row key to build from.
 */
export function defaultRecordConfig(
  definition: DataViewDefinition,
  limits: RuntimeLimits = DEFAULT_RUNTIME_LIMITS,
): RecordViewConfig {
  const capability = definition.record;
  if (!capability)
    throw new Error(
      `Definition ${definition.id} declares no record capability`,
    );

  // A field-less kind's name is a handle for the editor, not a path into a
  // row, so a column on one would be empty for every record ever shown.
  const names = definition.fields
    .filter(field => !isFieldlessKind(field.kind))
    .map(field => field.name);
  const defaults = capability.defaults ?? {};
  const pageSize = Math.min(
    defaults.pageSize ?? limits.defaultPageSize,
    limits.maxPageSize,
  );

  return {
    filter: defaults.filter ?? emptyFilter(),
    filterMode: defaults.filterMode ?? 'simple',
    refresh: defaults.refresh ?? { interval: null },
    kind: 'record',
    sort: defaults.sort ?? [],
    pageSize,
    // Written only when there is one. A config is JSON, and a `summaries`
    // member that is `undefined` is not the same object as no member at
    // all — which is what `dirty` compares, and what a store round-trips.
    ...(defaults.summaries ? { summaries: defaults.summaries } : {}),
    layout: defaults.layout ?? capability.layouts[0],
    table: defaults.table ?? {
      columns: names.slice(0, limits.defaultColumns).map(field => ({ field })),
    },
    card: defaults.card ?? {
      title: capability.rowKey,
      fields: names
        .filter(name => name !== capability.rowKey)
        .slice(0, limits.defaultCardFields),
    },
  };
}
