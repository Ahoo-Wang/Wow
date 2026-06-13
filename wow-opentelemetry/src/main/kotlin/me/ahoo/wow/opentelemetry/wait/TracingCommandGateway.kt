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

package me.ahoo.wow.opentelemetry.wait

import io.opentelemetry.context.Context
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.TraceFlux
import me.ahoo.wow.opentelemetry.TraceMono
import me.ahoo.wow.opentelemetry.Traced
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TracingCommandGateway(override val delegate: CommandGateway) : Traced, CommandGateway, Decorator<CommandGateway> {
    override fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Flux<CommandResult> {
        return TraceFlux(
            parentContext = Context.current(),
            instrumenter = WaitPlanInstrumenter.INSTRUMENTER,
            request = command,
            source = delegate.sendAndWaitStream(command, waitPlan),
        )
    }

    override fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Mono<CommandResult> {
        return TraceMono(
            parentContext = Context.current(),
            instrumenter = WaitPlanInstrumenter.INSTRUMENTER,
            request = command,
            source = delegate.sendAndWait(command, waitPlan),
        )
    }

    override fun send(message: CommandMessage<*>): Mono<Void> {
        return delegate.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        return delegate.receive(namedAggregates)
    }
}
