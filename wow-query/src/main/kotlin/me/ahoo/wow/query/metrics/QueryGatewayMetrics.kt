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

package me.ahoo.wow.query.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.expression.MatchNone
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableLogicalExpression
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class QueryGatewayMetrics(
    private val meterRegistry: MeterRegistry?,
    enabledCapabilities: Set<QueryCapabilityId>
) {
    private val enabledCapabilities: Set<QueryCapabilityId> =
        Collections.unmodifiableSet(LinkedHashSet(enabledCapabilities))

    fun state(request: QueryRequest, operation: QueryOperation): QueryGatewayMetricState = QueryGatewayMetricState(
        request = request,
        operation = operation,
        capabilityId = capabilityTag(request.expression)
    )

    fun <T : Any> observe(publisher: Mono<T>, state: QueryGatewayMetricState): Mono<T> {
        val recorded = AtomicBoolean()
        return publisher.doOnEach { signal ->
            when {
                signal.isOnError -> {
                    state.error.set(signal.throwable)
                    recordOnce(SignalType.ON_ERROR, state, recorded)
                }

                signal.isOnComplete -> recordOnce(SignalType.ON_COMPLETE, state, recorded)
            }
        }.doOnCancel { recordOnce(SignalType.CANCEL, state, recorded) }
    }

    fun <T : Any> observe(publisher: Flux<T>, state: QueryGatewayMetricState): Flux<T> {
        val recorded = AtomicBoolean()
        return publisher.doOnEach { signal ->
            when {
                signal.isOnError -> {
                    state.error.set(signal.throwable)
                    recordOnce(SignalType.ON_ERROR, state, recorded)
                }

                signal.isOnComplete -> recordOnce(SignalType.ON_COMPLETE, state, recorded)
            }
        }.doOnCancel { recordOnce(SignalType.CANCEL, state, recorded) }
    }

    private fun recordOnce(signal: SignalType, state: QueryGatewayMetricState, recorded: AtomicBoolean) {
        if (recorded.compareAndSet(false, true)) {
            record(signal, state)
        }
    }

    private fun record(signal: SignalType, state: QueryGatewayMetricState) {
        val registry = meterRegistry ?: return
        val error = state.error.get()
        val outcome = when (signal) {
            SignalType.ON_COMPLETE -> "success"
            SignalType.CANCEL -> "cancel"
            else -> "failure"
        }
        val errorCode = (error as? QueryException)?.code?.name ?: if (error == null) "none" else "BACKEND_FAILURE"
        registry.counter(
            METER_NAME,
            listOf(
                Tag.of("operation", state.operation.name.lowercase()),
                Tag.of("documentKind", state.request.target.documentKind.name.lowercase()),
                Tag.of("backendId", state.backendId.get()),
                Tag.of("outcome", outcome),
                Tag.of("errorCode", errorCode),
                Tag.of("capabilityId", state.capabilityId),
                Tag.of("policyDescriptor", "combined"),
                Tag.of("legacyFacade", "false")
            )
        ).increment()
    }

    private fun capabilityTag(expression: QueryExpression): String {
        val capabilities = linkedSetOf<QueryCapabilityId>()
        val pending = ArrayDeque<QueryExpression>()
        pending += expression
        while (pending.isNotEmpty()) {
            when (val current = pending.removeLast()) {
                is FullTextExpression -> capabilities += current.capabilityId
                is NativeExpression -> capabilities += current.capabilityId
                is LogicalExpression -> current.operands.forEach(pending::addLast)
                is PortableLogicalExpression -> current.operands.forEach(pending::addLast)
                is ElementMatchExpression -> pending += current.predicate
                MatchAll,
                MatchNone,
                is PredicateExpression -> Unit
            }
        }
        return when (capabilities.size) {
            0 -> "none"
            1 -> capabilities.single().takeIf { it in enabledCapabilities }?.value ?: "unsupported"
            else -> "multiple"
        }
    }

    private companion object {
        const val METER_NAME: String = "wow.query.gateway"
    }
}

internal class QueryGatewayMetricState(
    val request: QueryRequest,
    val operation: QueryOperation,
    val capabilityId: String
) {
    val backendId: AtomicReference<String> = AtomicReference("unresolved")
    val error: AtomicReference<Throwable?> = AtomicReference()
}
