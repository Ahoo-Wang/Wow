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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.naming.Named

class TraceMessagePropagator : MessagePropagator {
    companion object {
        private const val TRACE_ID = "trace_id"
        private const val UPSTREAM_ID = "upstream_id"
        private const val UPSTREAM_NAME = "upstream_name"

        val Header.traceId: String?
            get() {
                return this[TRACE_ID]
            }

        fun Header.withTraceId(traceId: String): Header {
            return this.with(TRACE_ID, traceId)
        }

        val Header.upstreamId: String?
            get() {
                return this[UPSTREAM_ID]
            }

        fun Header.withUpstreamId(upstreamId: String): Header {
            return this.with(UPSTREAM_ID, upstreamId)
        }

        val Header.upstreamName: String?
            get() {
                return this[UPSTREAM_NAME]
            }

        fun Header.withUpstreamName(upstreamName: String): Header {
            return this.with(UPSTREAM_NAME, upstreamName)
        }

        fun <C : Any> CommandMessage<C>.ensureTraceId(): CommandMessage<C> {
            if (header.traceId == null) {
                header.withTraceId(id)
            }
            return this
        }
    }

    override fun propagate(header: Header, upstream: Message<*, *>) {
        upstream.header.traceId?.let {
            header.withTraceId(it)
        }
        header.withUpstreamId(upstream.id)
        if (upstream is Named) {
            header.withUpstreamName(upstream.name)
        }
    }
}
