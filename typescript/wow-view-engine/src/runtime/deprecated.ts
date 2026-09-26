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
 * The deprecated fields a config uses (#3519): still queried, and said, so
 * the reader and whoever maintains the view move off them. Only the fields
 * a query names are asked about — the conditions, the sort, the columns and
 * cards on screen, the summaries, an analysis's dimensions and metrics.
 */

import {
  columnHidden,
  groupFieldsOf,
  type DataViewConfig,
  type FieldDefinition,
  type Issue,
} from '../model/index.js';
import { filterFields, issue } from '../filter/index.js';

export function deprecatedUses(
  fields: readonly FieldDefinition[],
  config: DataViewConfig,
): Issue[] {
  const deprecated = new Map(
    fields
      .filter(field => field.deprecated !== undefined)
      .map(field => [field.name, field] as const),
  );
  if (deprecated.size === 0) return [];
  const used = new Set(namedFields(config));
  return [...deprecated.values()]
    .filter(field => used.has(field.name))
    .map(field => {
      const reason = field.deprecated?.message;
      return reason === undefined
        ? issue('view.field.deprecated', [], { field: field.label }, 'warning')
        : issue(
            'view.field.deprecated-because',
            [],
            { field: field.label, reason },
            'warning',
          );
    });
}

function namedFields(config: DataViewConfig): string[] {
  const names = [...filterFields(config.filter)];
  if (config.kind === 'record') {
    names.push(...config.sort.map(entry => entry.field));
    names.push(
      ...config.table.columns
        .filter(column => !columnHidden(column.hidden))
        .map(column => column.field),
    );
    if (config.layout === 'card')
      names.push(config.card.title, ...config.card.fields);
    names.push(...(config.summaries ?? []).map(summary => summary.field));
    return names;
  }
  names.push(...config.groups.flatMap(groupFieldsOf));
  for (const metric of config.metrics) {
    if (metric.type === 'ANY') names.push(metric.field);
    else if (metric.type === 'FIRST' || metric.type === 'LAST')
      names.push(metric.field, ...(metric.orderBy ? [metric.orderBy] : []));
    else if (
      'expression' in metric &&
      metric.expression &&
      'field' in metric.expression &&
      typeof metric.expression.field === 'string'
    )
      names.push(metric.expression.field);
  }
  return names;
}
