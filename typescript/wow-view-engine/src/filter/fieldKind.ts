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

import type { FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FieldDefinition,
  FilterValue,
  FieldKindId,
  FieldOption,
  FilterLeaf,
  FilterOperatorName,
  Issue,
  IssuePath,
} from '../model/index.js';

/**
 * Everything the engine needs to know about one field type.
 *
 * A kind owns the shape of its values, so an application can add a type
 * without the kernel learning anything about it. The React editor registers
 * separately under the same id: a kind stays free of components, which is why
 * a saved config never holds a component name.
 */
export interface FieldKind {
  id: FieldKindId;
  /** Operators this kind supports, in the order an editor should offer them. */
  operators: FilterOperatorName[];
  defaultOperator: FilterOperatorName;
  /** The value to start from when a leaf switches to this operator. */
  emptyValue(operator: FilterOperatorName, field: FieldDefinition): unknown;
  /** Reports why a value cannot be used; an empty array admits it. */
  validate(context: FieldKindValidateContext): Issue[];
  /** Maps one admitted leaf onto the Wow protocol. */
  compile(context: FieldKindCompileContext): FilterExpression;
  /** Describes the editor implied by the operator and the value's variant. */
  editor(
    operator: FilterOperatorName,
    field: FieldDefinition,
    value?: unknown,
  ): EditorDescriptor;
  /** One line describing an applied condition, for the summary bar. */
  describe(context: FieldKindDescribeContext): string;
}

export interface FieldKindValidateContext {
  value: unknown;
  operator: FilterOperatorName;
  field: FieldDefinition;
  /** Location of the leaf, so an issue points at the offending node. */
  path: IssuePath;
}

export interface FieldKindCompileContext {
  leaf: FilterLeaf;
  field: FieldDefinition;
  /** Evaluation moment and zone; the kernel never reads the system clock. */
  now: Date;
  timeZone: string;
}

export interface FieldKindDescribeContext {
  leaf: FilterLeaf;
  field: FieldDefinition;
}

/** Shape of the input an editor should render; never a component name. */
export interface EditorDescriptor {
  input:
    | 'none'
    | 'text'
    | 'number'
    | 'boolean'
    | 'select'
    | 'remote'
    | 'date'
    | 'dateRange'
    | 'relativeDate';
  /** The input collects several values, e.g. for `IN`. */
  multiple?: boolean;
  /** Two bounds rather than one value, e.g. for `BETWEEN`. */
  range?: boolean;
  /** Static candidates, for `select`. */
  options?: FieldOption[];
  /** Candidate source key, for `remote`. */
  remote?: string;
  /** Whether the value carries a time of day. */
  withTime?: boolean;
}

/** Kinds available to the kernels, keyed by id. */
export type FieldKindRegistry = ReadonlyMap<FieldKindId, FieldKind>;

export function createFieldKindRegistry(
  kinds: readonly FieldKind[],
): FieldKindRegistry {
  return new Map(kinds.map(kind => [kind.id, kind]));
}

/**
 * Adds or replaces kinds, so an application extends the built-in set without
 * mutating it.
 */
export function withFieldKinds(
  registry: FieldKindRegistry,
  kinds: readonly FieldKind[],
): FieldKindRegistry {
  const merged = new Map(registry);
  for (const kind of kinds) merged.set(kind.id, kind);
  return merged;
}

/** Operators a field actually offers: its own list narrows its kind's. */
export function operatorsOf(
  field: FieldDefinition,
  kind: FieldKind,
): FilterOperatorName[] {
  if (!field.operators) return [...kind.operators];
  return field.operators.filter(operator => kind.operators.includes(operator));
}

/**
 * Reads an admitted leaf value as the shape its kind defined. Only
 * `validateFilter` decides admissibility; a compile step runs after it.
 */
export function readValue<T>(value: unknown): T {
  return value as T;
}

/**
 * Stores a value a kind produced, the mirror of `readValue`.
 *
 * A kind owns the shape of its values and declares it as an interface, which
 * TypeScript does not consider assignable to a JSON index signature even when
 * every field is JSON. Admission still runs on the way back in.
 */
export function writeValue<T>(value: T): FilterValue {
  return value as FilterValue;
}

export function issue(
  code: string,
  path: IssuePath,
  params?: Issue['params'],
  severity: Issue['severity'] = 'error',
): Issue {
  return params ? { code, severity, path, params } : { code, severity, path };
}
