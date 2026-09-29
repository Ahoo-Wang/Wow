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
 * `@ahoo-wang/wow-view-engine/testing`: an in-memory Wow source for a host's
 * tests and demos (D65). Headless — no React, no DOM, no stylesheet — and
 * held to the server's semantics matrix (`FilterSemantics`) and its TCK by
 * the package's own suites.
 */
export { memorySource, type MemorySourceOptions } from './source.js';
export { matches } from './filter.js';
export {
  admit,
  type Admissible,
  type AdmitFinding,
  type AdmitOptions,
} from '../runtime/admission.js';
export { resolveNavigation } from '../runtime/routes.js';
export {
  actionHarness,
  type ActionHarness,
  type ActionHarnessOptions,
  type HarnessBulk,
  type HarnessField,
  type HarnessState,
} from './actions.js';
export { ActionRefused } from '../runtime/actions.js';
