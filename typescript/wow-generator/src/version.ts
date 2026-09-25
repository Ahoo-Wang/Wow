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

// Replaced at build time with the `version` of this package's package.json
// (`define` in vite.config.ts, which Vitest shares), so only the version string
// reaches dist, not the manifest with its dependencies and workspace ranges.
declare const __WOW_GENERATOR_VERSION__: string;

/** The version of this package, as its package.json named it at build time. */
export const VERSION: string = __WOW_GENERATOR_VERSION__;
