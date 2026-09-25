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
 * The server's capability descriptor, adopted (capabilities.md, N5): a
 * definition narrowed to what one deployment admits, the limits a source
 * says, and the cache that reads and revalidates the descriptor. A peer of
 * the four kernels: it imports `model` and `filter` only, and none of them
 * imports it.
 */
export * from './cache.js';
export * from './limits.js';
export * from './narrow.js';
