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
 * A store a port called could not be reached, or did not answer in time: a transient technical failure, such as a
 * connection refused, reset or closed by a restarting server, or a connection pool or socket that timed out. The same
 * call may succeed once the store is back.
 *
 * A backend throws it, with the driver's exception as [cause], from the calls that read (for the event log, every call
 * but an append, whose outcome stays a value: [me.ahoo.wow.spi.log.AppendOutcome.Unknown]). Any other failure stays
 * the driver's own exception: a defect, not something a retry fixes.
 *
 * The runtime answers it as the error `wow.storage.unavailable`, which a caller may retry: a command with the same
 * request id, which the kernel deduplicates, a query as it is. A store that is up but at its capacity is the subtype
 * [StorageBusyException].
 */
open class StorageUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
