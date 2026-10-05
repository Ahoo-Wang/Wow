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

/**
 * The ServiceLoader propagators as a process-wide singleton.
 *
 * compat(wow<9.3): code written against 9.2 propagates through this object; it delegates to
 * [MessagePropagators.DEFAULT]. The runtime uses an injected [MessagePropagators] instead.
 */
@Deprecated("Scheduled for removal in 10.0.0. Use an injected MessagePropagators, or MessagePropagators.DEFAULT.")
object MessagePropagatorProvider : MessagePropagator {
    override fun propagate(
        header: Header,
        upstream: Message<*, *>
    ) {
        MessagePropagators.DEFAULT.propagate(header, upstream)
    }

    /**
     * Propagates [upstream]'s context to this header and returns it.
     */
    @Deprecated(
        "Scheduled for removal in 10.0.0. Use Header.propagate(upstream, propagators).",
        ReplaceWith("propagate(upstream, MessagePropagators.DEFAULT)"),
    )
    fun Header.propagate(upstream: Message<*, *>): Header {
        MessagePropagators.DEFAULT.propagate(this, upstream)
        return this
    }
}
