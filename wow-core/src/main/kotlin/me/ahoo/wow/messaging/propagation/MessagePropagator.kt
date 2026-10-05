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

package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionInfo

/**
 * Interface for message propagators that transfer context information from upstream messages to headers.
 *
 * Message propagators are responsible for copying relevant context data (like tracing information,
 * user details, etc.) from source messages to target message headers for distributed tracing
 * and context propagation.
 *
 * The runtime calls the propagators of a [MessagePropagators] registry: the Spring bean, made of the ServiceLoader
 * contributions and the `MessagePropagator` beans, or [MessagePropagators.DEFAULT] outside Spring.
 */
interface MessagePropagator {
    /**
     * Propagates context information from an upstream message to the target header.
     *
     * @param header The target message header to receive propagated context data
     * @param upstream The upstream message providing context information to propagate
     */
    fun propagate(
        header: Header,
        upstream: Message<*, *>
    )

    /**
     * Propagates context from [upstream] to the header of a message that [producer] creates from it, such as a
     * command a saga function sends for an event. A propagator whose keys belong to one downstream function only
     * (the wait chain) uses [producer] to decide; the default ignores it.
     */
    fun propagate(
        header: Header,
        upstream: Message<*, *>,
        producer: FunctionInfo,
    ) = propagate(header, upstream)
}
