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

// Internal: the index of this folder does not re-export this file. The
// paths are relative to the client's base path.
export const SnapshotQueryEndpointPaths = Object.freeze({
  AGGREGATION: 'snapshot/aggregation',
  COUNT: 'snapshot/count',
  LIST: 'snapshot/list',
  LIST_STATE: 'snapshot/list/state',
  PAGED: 'snapshot/paged',
  PAGED_STATE: 'snapshot/paged/state',
  CURSOR: 'snapshot/cursor',
  CURSOR_STATE: 'snapshot/cursor/state',
  SINGLE: 'snapshot/single',
  SINGLE_STATE: 'snapshot/single/state',
} as const);
