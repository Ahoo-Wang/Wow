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

/*
 * Not re-exported by `dashboard/index.ts`: the grammar of a page click's
 * URL template, which the click kernel fills and `panelFieldNames.ts`
 * renames, and nothing outside the kernel reads.
 */

/** `{{ name }}`, spaces inside the braces allowed; the name has none. */
export const URL_PLACEHOLDER = /\{\{\s*([^{}\s]+)\s*\}\}/g;
