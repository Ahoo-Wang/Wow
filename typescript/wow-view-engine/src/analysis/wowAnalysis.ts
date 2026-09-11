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

import { MAX_ANALYSIS_ELEMENTS } from './analysisModel.js';
import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType as Group,
  FilterOperator as Op,
  aggregation,
} from '@ahoo-wang/fetcher-wow';
import type { FilterFieldDefinition } from '../filter/filterModel.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import type {
  AnalysisCapability,
  AnalysisScopeDefinition,
} from './analysisModel.js';

export interface WowAnalysisSchemaOptions {
  /** Keys are absolute logical paths, including parent element paths. */
  labels?: Readonly<Record<string, string>>;
  units?: Readonly<Record<string, string>>;
}
export interface WowAnalysisSchema {
  model: string;
  fields: FilterFieldDefinition[];
  capability: AnalysisCapability;
}
function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}
function strings(value: unknown): string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
    ? value
    : [];
}
function override(
  values: Readonly<Record<string, string>> | undefined,
  field: string,
): string | undefined {
  return values &&
    Object.prototype.hasOwnProperty.call(values, field) &&
    typeof values[field] === 'string'
    ? values[field]
    : undefined;
}
/** Read-only Wow Schema projection. Unknown/masked/union values and non-millisecond
 * temporal encodings are omitted; arrays become declared object-element scopes only.
 * No network, coercion, field capability invention, or scalarization of root arrays.
 */
export function adaptWowAnalysisSchema(
  schema: unknown,
  options: WowAnalysisSchemaOptions = {},
): WowAnalysisSchema {
  validateFilterJson(schema);
  const envelope = record(schema),
    root = record(envelope?.root);
  if (
    !envelope ||
    typeof envelope.model !== 'string' ||
    !/^[A-Za-z_][A-Za-z0-9_-]*$/.test(envelope.model) ||
    root?.kind !== 'OBJECT' ||
    root.masked !== false ||
    !record(root.properties)
  )
    throw new TypeError('Wow Schema model/root 无效');
  const scopes: AnalysisScopeDefinition[] = [];
  const collect = (
    container: Record<string, unknown>,
    absolute: string,
    chain: AnalysisScopeDefinition['elements'],
  ): Pick<WowAnalysisSchema, 'fields' | 'capability'> => {
    const fields: FilterFieldDefinition[] = [];
    const capability: AnalysisCapability = {
      fields: [],
      count: true,
      expressions: true,
    };
    const arrays: {
      node: Record<string, unknown>;
      path: string;
      absolute: string;
    }[] = [];
    const walk = (
      node: Record<string, unknown>,
      path: string,
      fullPath: string,
    ) => {
      if (node.masked !== false) return;
      if (node.kind === 'OBJECT') {
        for (const [name, value] of Object.entries(
          record(node.properties) ?? {},
        )) {
          if (!/^[A-Za-z_][A-Za-z0-9_-]*$/.test(name)) continue;
          const child = record(value);
          if (child)
            walk(
              child,
              path ? `${path}.${name}` : name,
              fullPath ? `${fullPath}.${name}` : name,
            );
        }
        return;
      }
      const caps = strings(node.capabilities);
      if (node.kind === 'ARRAY') {
        const items = record(node.items);
        if (
          caps.includes('ELEMENT_SCOPE') &&
          items?.kind === 'OBJECT' &&
          items.masked === false &&
          chain.length < MAX_ANALYSIS_ELEMENTS
        )
          arrays.push({ node: items, path, absolute: fullPath });
        return;
      }
      if (node.kind !== 'SCALAR' || !path) return;
      const types = strings(node.valueTypes);
      if (types.length !== 1) return;
      const semantic = record(node.semanticType);
      let type: FilterFieldDefinition['type'];
      if (semantic) {
        if (
          semantic.type !== 'TEMPORAL_EPOCH' ||
          semantic.timeUnit !== 'MILLISECONDS' ||
          !['INTEGER', 'DECIMAL'].includes(types[0])
        )
          return;
        type = 'datetime';
      } else
        type =
          types[0] === 'STRING'
            ? 'string'
            : types[0] === 'BOOLEAN'
              ? 'boolean'
              : ['INTEGER', 'DECIMAL'].includes(types[0])
                ? 'number'
                : undefined;
      if (!type) return;
      const operators: Op[] = [];
      if (caps.includes('PRESENCE'))
        operators.push(Op.EXISTS, Op.NOT_EXISTS, Op.IS_NULL, Op.IS_NOT_NULL);
      if (caps.includes('EXACT_MATCH'))
        operators.push(Op.EQ, Op.NE, Op.IN, Op.NOT_IN);
      if (caps.includes('RANGE') && type !== 'boolean')
        operators.push(Op.GT, Op.GTE, Op.LT, Op.LTE, Op.BETWEEN);
      if (caps.includes('LITERAL_MATCH') && type === 'string')
        operators.push(Op.CONTAINS, Op.STARTS_WITH, Op.ENDS_WITH);
      const groups: Group[] = [];
      if (caps.includes('AGGREGATE_TERMS') && type !== 'datetime')
        groups.push(Group.TERMS);
      if (caps.includes('AGGREGATE_NUMERIC') && type === 'number')
        groups.push(Group.HISTOGRAM);
      if (caps.includes('AGGREGATE_TEMPORAL') && type === 'datetime')
        groups.push(Group.DATE_HISTOGRAM);
      const functions =
        caps.includes('AGGREGATE_NUMERIC') && type === 'number'
          ? Object.values(AggregationFunction)
          : [];
      const any = caps.includes('AGGREGATE_TERMS');
      if (!operators.length && !groups.length && !functions.length && !any)
        return;
      aggregation.field(path);
      const label =
        override(options.labels, fullPath) ??
        (typeof node.title === 'string' && node.title ? node.title : fullPath);
      const enumValues = node.enumValues;
      const enumOptions =
        Array.isArray(enumValues) &&
        enumValues.length > 0 &&
        ['string', 'number', 'boolean'].includes(type) &&
        enumValues.every(
          value =>
            typeof value === type &&
            (type !== 'number' || Number.isFinite(value)),
        )
          ? enumValues.map(value => ({
              value: value as string | number | boolean,
              label: String(value),
            }))
          : undefined;
      fields.push({
        field: path,
        label,
        type,
        operators,
        ...(enumOptions ? { options: enumOptions } : {}),
      });
      capability.fields.push({
        field: path,
        groups,
        functions,
        any,
        ...(groups.includes(Group.DATE_HISTOGRAM)
          ? { dateUnits: Object.values(AggregationDateUnit) }
          : {}),
        ...(override(options.units, fullPath)
          ? { unit: override(options.units, fullPath) }
          : {}),
      });
    };
    walk(container, '', absolute);
    for (const array of arrays) {
      const element = {
        path: array.path,
        fields: [] as FilterFieldDefinition[],
      };
      const elements = [...chain, element];
      const index = scopes.length;
      const scope: AnalysisScopeDefinition = {
        id: array.absolute,
        label: override(options.labels, array.absolute) ?? array.absolute,
        elements,
        fields: [],
        capability: { fields: [], count: true, expressions: true },
      };
      scopes.push(scope);
      const child = collect(array.node, array.absolute, elements);
      element.fields = child.fields;
      scopes[index] = {
        ...scope,
        fields: child.fields,
        capability: child.capability,
      };
    }
    return { fields, capability };
  };
  const mapped = collect(root, '', []);
  return {
    model: envelope.model,
    fields: mapped.fields,
    capability: { ...mapped.capability, scopes },
  };
}
