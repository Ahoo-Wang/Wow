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

// validate.ts and scope.ts are internal and stay unlisted,
// and so are the groups `filter` is composed of (metadataFilters.ts,
// logicalFilters.ts, fieldFilters.ts, searchFilter.ts, timeFilters.ts,
// expressionFilter.ts): `filter` is their one public face.
export * from './operator.js';
export * from './types.js';
export { filter } from './builders.js';
export type { SearchBuilder } from './searchFilter.js';
