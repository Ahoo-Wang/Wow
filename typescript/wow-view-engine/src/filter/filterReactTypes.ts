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

import type { FilterOptionSource } from './filterOptionSource.js';
import type { ComponentType, ReactNode } from 'react';
import type { FilterOperator } from '@ahoo-wang/fetcher-wow';
import type { FilterOption } from './filterTypes.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  FilterCompiler,
  FilterCompilerContext,
  FilterComponentProperties,
  FilterConfiguration,
  FilterApplyResult,
  FilterEditorReference,
  FilterFieldDefinition,
  FilterJsonValue,
  FilterMode,
} from './filterModel.js';

/** UI-library-independent value editor. Inputs are read-only snapshots, not applied query state. */
export interface FilterEditorProps {
  timeZone?: string;
  errors?: readonly string[];
  errorId?: string;
  optionSources?: Readonly<Record<string, FilterOptionSource>>;
  props: DeepReadonly<FilterComponentProperties>;
  operator: FilterOperator;
  field?: DeepReadonly<FilterFieldDefinition>;
  fields: DeepReadonly<readonly FilterFieldDefinition[]>;
  mode: FilterMode;
  context?: unknown;
  options?: DeepReadonly<Record<string, FilterJsonValue>>;
  disabled: boolean;
  /** Publish raw serializable component properties. Never queries or changes the binding. */
  onChange(props: FilterComponentProperties): void;
  /** Invalid local buffers must report false; an empty message still blocks application. */
  onValidityChange(valid: boolean, message?: string): void;
}
/** Complete non-container UI. The panel still owns layout, errors, binding, compilation and Query. */
export interface FilterComponentProps extends FilterEditorProps {
  /** Stable DOM-safe identity for this mounted panel/node; never persist it. */
  readonly id: string;
  /** Labels and capability restrictions for the current binding and mode. */
  readonly operators: DeepReadonly<readonly FilterOption<FilterOperator>[]>;
  readonly errors: readonly string[];
  /** Connect inputs with aria-describedby when the panel displays an error. */
  readonly errorId?: string;
  /** Use the panel's operator transition rules, preserving compatible values and pending invalid input. */
  onOperatorChange(operator: FilterOperator): void;
  /** Available when registration supplies clear semantics; remounts to discard local buffers. */
  onClear?(): void;
  /** Removes the whole node. Does not apply or save. */
  onRemove(): void;
}
/** One filter definition owns rendering, pure compilation and optional clearing. */
export interface FilterEditorRegistration extends FilterCompiler {
  /** Default: compose this component inside the built-in field/operator/remove frame. */
  render?: 'value';
  component: ComponentType<FilterEditorProps>;
  modes: readonly FilterMode[];
  supports?: (
    props: DeepReadonly<FilterComponentProperties>,
    context: FilterCompilerContext,
  ) => boolean;
}
export interface FilterComponentRegistration extends Omit<
  FilterEditorRegistration,
  'component' | 'render'
> {
  /** Render the complete non-container UI, without the built-in frame. */
  render: 'filter';
  component: ComponentType<FilterComponentProps>;
}
export type FilterRegistration =
  FilterEditorRegistration | FilterComponentRegistration;
export interface FilterExtensions {
  optionSources?: Readonly<Record<string, FilterOptionSource>>;
  filters?: Readonly<Record<string, FilterRegistration>>;
}
/** Compose panel controls elsewhere without bypassing the panel's mode transition guards. */
export interface FilterPanelToolbarProps {
  panelId: string;
  mode: FilterMode;
  options: readonly FilterOption<FilterMode>[];
  pending: boolean;
  disabled: boolean;
  onModeChange(mode: FilterMode): void;
}
export type FilterPanelProps = FilterPanelOptions &
  (
    | {
        value: DeepReadonly<FilterConfiguration>;
        onChange(configuration: FilterConfiguration): void;
        defaultValue?: never;
      }
    | {
        value?: never;
        defaultValue?: DeepReadonly<FilterConfiguration>;
        onChange?(configuration: FilterConfiguration): void;
      }
  );
interface FilterPanelOptions {
  /** Accessible name for multiple filter panels in a composed page. */
  ariaLabel?: string;
  /** Shared by all date/time controls and compilation; defaults to local. */
  timeZone?: string;
  fields: readonly FilterFieldDefinition[];
  onApply(result: FilterApplyResult): void | Promise<void>;
  onPendingChange?(pending: boolean): void;
  /** Last successfully applied configuration, including unset controls. */
  appliedValue?: DeepReadonly<FilterConfiguration>;
  /** Reports local buffer and editor validity; pending remains derived. */
  onValidityChange?(valid: boolean): void;
  allowedOperators?: readonly FilterOperator[];
  extensions?: FilterExtensions;
  editors?: Readonly<Partial<Record<FilterOperator, FilterEditorReference>>>;
  context?: unknown;
  /** Hide query actions and shortcuts when a parent owns execution; defaults to true. */
  showQueryAction?: boolean;
  querying?: boolean;
  queryError?: ReactNode;
  disabled?: boolean;
  /** Hide the body while retaining mounted editors and their local buffers. */
  collapsed?: boolean;
  /** Replaces the default header; rendered before the collapsible panel body. */
  renderToolbar?(props: FilterPanelToolbarProps): ReactNode;
  className?: string;
}

/** Narrow builtin input view; compilation remains responsible for validating raw properties. */
export interface BuiltinFilterProperties {
  value?: FilterJsonValue;
  values?: FilterJsonValue[];
  lowerBound?: FilterJsonValue;
  upperBound?: FilterJsonValue;
  query?: string;
  fields?: string[];
  mode?: string;
  state?: string;
  time?: string;
  days?: number | string;
  stringComparison?: string;
  datePattern?: string;
  timeUnit?: string;
}
