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

package me.ahoo.wow.opentelemetry.aggregate

import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.modeling.command.dispatcher.CommandInstrumentation
import me.ahoo.wow.opentelemetry.ExchangeTraceMono
import me.ahoo.wow.opentelemetry.ReactorTraceContext
import me.ahoo.wow.opentelemetry.Traced
import reactor.core.publisher.Mono

/**
 * Traces the handling of each command with one consumer span named `<aggregate>.<command>`, as the command filter
 * `TraceAggregateFilter` did before 9.3.0: same instrumenter, span name and attributes, around the whole command
 * pipeline.
 */
class TraceCommandInstrumentation(
    private val instrumenter: Instrumenter<ServerCommandExchange<*>, Unit> = AggregateInstrumenter.INSTRUMENTER
) : Traced, CommandInstrumentation {
    override fun around(exchange: ServerCommandExchange<*>, handling: Mono<Void>): Mono<Void> =
        Mono.deferContextual {
            ExchangeTraceMono(ReactorTraceContext.get(it), instrumenter, exchange, handling)
        }
}
