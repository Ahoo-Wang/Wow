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

package me.ahoo.wow.spi

/**
 * A store is up but already runs as much as it takes, and did not admit this call: a query store's bulkhead, say.
 * The same call may pass once the load drops, so it is retried with backoff, never right away: a subscription throttles
 * its partition and retries the entry within its backpressure budget, without pausing the partition.
 *
 * Whatever bounds the store's load throws it, as the cause of the error it answers the caller with
 * (`wow.query.busy`, say).
 */
class StorageBusyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
