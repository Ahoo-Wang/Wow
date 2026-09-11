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

import type { ComponentType } from 'react';
import type { DeepReadonly } from '../lib/types.js';
import type { FilterValidationError } from '../filter/filterModel.js';
import type {
  AnalysisComponentConfig,
  AnalysisComponentCompileContext,
  AnalysisCompiler,
} from './analysisModel.js';
export interface AnalysisComponentEditorProps {
  value: DeepReadonly<AnalysisComponentConfig>;
  context: DeepReadonly<AnalysisComponentCompileContext>;
  onChange(value: AnalysisComponentConfig): void;
  disabled?: boolean;
  errors?: readonly FilterValidationError[];
}
/** One protocol registration pairs its editor with its bounded pure contribution compiler. */
export interface AnalysisRegistration extends AnalysisCompiler {
  component: ComponentType<AnalysisComponentEditorProps>;
}
export interface AnalysisExtensions {
  analysis?: Readonly<Record<string, AnalysisRegistration>>;
}
