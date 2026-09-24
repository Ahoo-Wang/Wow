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
 * Root entry of `@ahoo-wang/wow-view-engine`.
 *
 * The headless layers, in the order `docs/design/` fixes: `model`, `filter`,
 * `record` / `analysis` / `dashboard`, `runtime`, `store`. React lives in
 * the `/react` and `/ui` entries of its own. Dependency rules between the
 * layers are enforced by `test/architecture.test.ts`.
 */
export * from './model/index.js';
export * from './filter/index.js';
export * from './record/index.js';
export * from './analysis/index.js';
export * from './dashboard/index.js';
export * from './runtime/index.js';
export * from './store/index.js';
