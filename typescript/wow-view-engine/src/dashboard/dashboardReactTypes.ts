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

import type { ComponentType, ReactNode } from 'react';
import type { ViewDefinition } from '../contracts/viewModel.js';
import type { FilterJsonValue } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type { ViewExtensions } from '../view/viewReactTypes.js';
import type { DashboardRuntime } from './DashboardRuntime.js';

export interface DashboardTransformEditorProps {
  value: Readonly<Record<string, FilterJsonValue>>;
  onChange(value: Record<string, FilterJsonValue>): void;
  onValidityChange(valid: boolean): void;
}
export interface DashboardTransformRegistration {
  label: string;
  applicable?(
    source: DeepReadonly<ViewDefinition>,
    target: DeepReadonly<ViewDefinition>,
  ): boolean;
  hasOptions?: boolean;
  Editor?: ComponentType<DashboardTransformEditorProps>;
}
/** Presentation only; execution is registered in ViewEngineOptions.dashboardTransforms. */
export interface DashboardExtensions {
  transforms?: Readonly<Record<string, DashboardTransformRegistration>>;
}
export interface DashboardViewProps {
  runtime: DashboardRuntime;
  extensions?: ViewExtensions;
  filterContext?: unknown;
  toolbarStart?: ReactNode;
  /** Contextual title and landmark prefix when composed into another page. */
  title?: string;
  className?: string;
}
