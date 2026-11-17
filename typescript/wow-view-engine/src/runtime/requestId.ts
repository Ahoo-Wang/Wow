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
 * Where the engine's request ids come from: the Wow client's `newRequestId`,
 * a UUIDv7 (RFC 9562) whose first 48 bits are the millisecond it was made
 * in, so a view store's writes reach the service's unique index on the
 * request id in the order they were made, as every other caller's do. Over
 * plain HTTP, where `crypto.randomUUID` is missing, it still has
 * `crypto.getRandomValues`.
 *
 * The engine keeps no generator of its own. `react` may not import the
 * client (`test/architecture.test.ts`), so it takes the generator from here.
 */
export { newRequestId } from '@ahoo-wang/wow-client';
