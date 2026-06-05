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

package me.ahoo.wow.messaging.function

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.CopyOnWriteArraySet

@State(Scope.Benchmark)
open class MessageFunctionRegistrarBenchmark {
    @Param("1024")
    var functionCount: Int = 0

    private lateinit var message: SimpleDomainEvent<BenchmarkEventBody>
    private lateinit var indexedRegistrar: MessageFunctionRegistrar<MessageFunction<*, *, *>>
    private lateinit var linearRegistrar: MessageFunctionRegistrar<MessageFunction<*, *, *>>

    @Setup
    fun setup() {
        val targetTopic = MaterializedNamedAggregate("benchmark", "target")
        message = SimpleDomainEvent(
            id = "event-id",
            body = BenchmarkEventBody,
            aggregateId = targetTopic.aggregateId("target-id"),
            version = 1,
            commandId = "command-id",
        )
        indexedRegistrar = SimpleMessageFunctionRegistrar()
        linearRegistrar = LinearMessageFunctionRegistrar()
        repeat(functionCount - 1) {
            val function = BenchmarkMessageFunction(
                topic = MaterializedNamedAggregate("benchmark", "aggregate-$it")
            )
            indexedRegistrar.register(function)
            linearRegistrar.register(function)
        }
        BenchmarkMessageFunction(topic = targetTopic).let {
            indexedRegistrar.register(it)
            linearRegistrar.register(it)
        }
    }

    @Benchmark
    fun indexedLookup(blackhole: Blackhole) {
        blackhole.consume(indexedRegistrar.supportedFunctions(message).count())
    }

    @Benchmark
    fun linearLookup(blackhole: Blackhole) {
        blackhole.consume(linearRegistrar.supportedFunctions(message).count())
    }
}

private object BenchmarkEventBody

private class BenchmarkMessageFunction(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Any> {
    override val supportedType: Class<*> = BenchmarkEventBody::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-${topic.aggregateName}"

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Any = Unit
}

private class LinearMessageFunctionRegistrar<F : MessageFunction<*, *, *>> : MessageFunctionRegistrar<F> {
    private val registrar: CopyOnWriteArraySet<F> = CopyOnWriteArraySet()

    override val functions: Set<F>
        get() = registrar

    override fun register(function: F) {
        registrar.add(function)
    }

    override fun unregister(function: F) {
        registrar.remove(function)
    }

    override fun filter(predicate: (F) -> Boolean): MessageFunctionRegistrar<F> {
        val filteredRegistrar = LinearMessageFunctionRegistrar<F>()
        filteredRegistrar.registrar.addAll(registrar.filter(predicate))
        return filteredRegistrar
    }

    override fun <M> supportedFunctions(message: M): Sequence<F>
        where M : Message<*, Any>,
              M : NamedBoundedContext,
              M : NamedAggregate =
        functions.asSequence()
            .filter {
                it.supportMessage(message)
            }
}
