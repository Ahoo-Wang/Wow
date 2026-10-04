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

package me.ahoo.wow.webflux.route.command.appender

import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.COMPENSATION_PREFIX
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withRemoteIp
import me.ahoo.wow.messaging.propagation.CommandRequestHeaderPropagator.Companion.withUserAgent
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withTraceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withUpstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.withUpstreamName
import me.ahoo.wow.messaging.withLocalFirst

/**
 * The command message header keys the framework itself writes and reads, which a `Command-Header-*` request header
 * must not set: the operator, local-first routing, the trace and upstream keys, the request's user agent and remote
 * IP, every wait key (`command_wait_*`, wait chain and tail included) and every compensation key (`compensate.*`).
 *
 * The exact keys come from the framework's own header writers, so they follow a rename; the two families come from
 * their prefix constants. The W3C trace context fields are added because the OpenTelemetry integration carries them in
 * the same header, and CoSec's `app_id` / `device_id` because the CoSec integration does. Tenant, owner, space,
 * aggregate id and request id are message fields, not header keys. Keys compare case-insensitively.
 */
internal object ReservedCommandHeaderKeys {
    private const val PLACEHOLDER = "-"
    private val W3C_TRACE_CONTEXT_FIELDS = setOf("traceparent", "tracestate", "baggage")

    /**
     * The keys `CoSecMessagePropagator` (wow-cosec) propagates; the app id is view-store's isolation key. They are
     * literals because wow-webflux cannot see wow-cosec; a wow-cosec test checks every key it writes is reserved.
     */
    private val COSEC_KEYS = setOf("app_id", "device_id")

    val keys: Set<String> = DefaultHeader.empty()
        .withOperator(PLACEHOLDER)
        .withLocalFirst()
        .withTraceId(PLACEHOLDER)
        .withUpstreamId(PLACEHOLDER)
        .withUpstreamName(PLACEHOLDER)
        .withUserAgent(PLACEHOLDER)
        .withRemoteIp(PLACEHOLDER)
        .keys
        .plus(W3C_TRACE_CONTEXT_FIELDS)
        .plus(COSEC_KEYS)
        .map { it.lowercase() }
        .toSet()

    val prefixes: List<String> = listOf(COMMAND_WAIT_PREFIX, COMPENSATION_PREFIX).map { it.lowercase() }

    fun isReserved(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized in keys || prefixes.any { normalized.startsWith(it) }
    }
}
