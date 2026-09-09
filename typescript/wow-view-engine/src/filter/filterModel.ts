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
  FilterExpression,
  FilterLiteral,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import type { DeepReadonly } from '../lib/types.js';
import type { FilterField } from './filterTypes.js';

export type FilterMode = 'simple' | 'advanced';
export type FilterFieldType =
  'string' | 'number' | 'boolean' | 'date' | 'datetime' | 'array';
export type FilterJsonValue =
  | null
  | string
  | number
  | boolean
  | readonly FilterJsonValue[]
  | { readonly [key: string]: FilterJsonValue | undefined };
export interface FilterEditorReference {
  /** Persisted protocol identity; incompatible property/compile semantics require a new name. */
  name: string;
  options?: Readonly<Record<string, FilterJsonValue>>;
}
export interface FilterFieldDefinition extends FilterField {
  /** Display group in the add-filter picker; groups follow definition order. */
  group?: string;
  type?: FilterFieldType;
  options?: readonly {
    value: Exclude<FilterLiteral, null>;
    label: string;
    disabled?: boolean;
    group?: string;
  }[];
  fields?: readonly FilterFieldDefinition[];
  operators?: readonly FilterOperator[];
  editor?: FilterEditorReference;
}
export interface FilterDateTimeValue {
  date?: string;
  time?: string;
  /** Date.getTimezoneOffset() integer minutes; retained when valid. Otherwise repeated local times choose the earlier occurrence. */
  offsetMinutes?: number;
}
/** Keeps an item's intended type while its raw text is temporarily incomplete. */
export interface FilterScalarDraftValue {
  type: 'string' | 'number' | 'boolean';
  value: unknown;
}
export interface FilterOperatorDefinition {
  label: string;
  category: 'logical' | 'element' | 'field' | 'root';
  input:
    | 'none'
    | 'value'
    | 'values'
    | 'between'
    | 'search'
    | 'deletion'
    | 'time'
    | 'days';
  relativeTime?: boolean;
}
export interface FilterValidationError {
  id: string;
  message: string;
}
export interface FilterCompileResult {
  expression?: FilterExpression;
  errors: FilterValidationError[];
}

/** Serializable component state, including unset controls and presentation properties. */
export type FilterComponentProperties = Record<
  string,
  FilterJsonValue | undefined
>;
export interface FilterComponentConfig {
  id: string;
  component: FilterEditorReference;
  operator: FilterOperator;
  field?: string;
  props: FilterComponentProperties;
  operands?: FilterComponentConfig[];
  predicate?: FilterComponentConfig;
}
export interface FilterConfiguration {
  mode: FilterMode;
  root: FilterComponentConfig;
}
export interface FilterCompilerContext {
  /** Global view/panel timezone; omitted uses the local runtime timezone. */
  timeZone?: string;
  operator: FilterOperator;
  field?: DeepReadonly<FilterFieldDefinition>;
  fields: DeepReadonly<readonly FilterFieldDefinition[]>;
  options?: DeepReadonly<Record<string, FilterJsonValue>>;
}
/** React-independent capabilities. React registers them together with its filter component. */
export interface FilterCompiler {
  compile(
    props: DeepReadonly<FilterComponentProperties>,
    context: FilterCompilerContext,
  ): FilterExpression | undefined;
  clear?(
    props: DeepReadonly<FilterComponentProperties>,
    context: FilterCompilerContext,
  ): FilterComponentProperties;
}
export type FilterCompilerRegistry = Readonly<Record<string, FilterCompiler>>;

export interface FilterApplyResult {
  configuration: FilterConfiguration;
  expression: FilterExpression;
}
