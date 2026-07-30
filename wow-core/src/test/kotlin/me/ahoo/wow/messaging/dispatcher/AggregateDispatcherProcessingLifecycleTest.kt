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

package me.ahoo.wow.messaging.dispatcher

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AggregateDispatcherProcessingLifecycleTest {

    @Test
    fun `quiesce revokes processing before detaching source cancellation`() {
        val calls = mutableListOf<String>()
        var detachedCancellation: Runnable? = null
        val dispatcher = ProcessingLifecycleDispatcher(
            messageFlux = Flux.never<ProcessingLifecycleExchange>()
                .doOnCancel {
                    calls += "cancel-source"
                },
            cleanupDispatcher = { cleanup ->
                calls += "schedule-cancellation"
                detachedCancellation = cleanup
                true
            },
            processingQuiescence = {
                calls += "close-processing"
            },
        )
        prepareAndStart(dispatcher)

        dispatcher.quiesce()

        calls.assert().containsExactly(
            "close-processing",
            "schedule-cancellation",
        )
        checkNotNull(detachedCancellation).run()
        calls.assert().containsExactly(
            "close-processing",
            "schedule-cancellation",
            "cancel-source",
        )
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
    }

    @Test
    fun `source termination revokes processing exactly once`() {
        val source = Sinks.many().unicast().onBackpressureBuffer<ProcessingLifecycleExchange>()
        val processingCloses = AtomicInteger()
        val dispatcher = ProcessingLifecycleDispatcher(
            messageFlux = source.asFlux(),
            processingQuiescence = processingCloses::incrementAndGet,
        )
        prepareAndStart(dispatcher)

        source.tryEmitComplete().orThrow()
        StepVerifier.create(dispatcher.stopGracefully()).verifyComplete()
        dispatcher.quiesce()

        processingCloses.get().assert().isOne()
    }

    private fun prepareAndStart(dispatcher: ProcessingLifecycleDispatcher) {
        dispatcher.prepare(DefaultRuntimeContext()).block()
        dispatcher.start()
    }
}

private class ProcessingLifecycleDispatcher(
    override val messageFlux: Flux<ProcessingLifecycleExchange>,
    cleanupDispatcher: (Runnable) -> Boolean = { action ->
        action.run()
        true
    },
    processingQuiescence: () -> Unit,
) : AggregateDispatcher<ProcessingLifecycleExchange>(
    cleanupDispatcher = cleanupDispatcher,
    processingQuiescence = processingQuiescence,
) {
    override val name: String = "processing-lifecycle"
    override val namedAggregate: NamedAggregate = ProcessingLifecycleMessage().materialize()
    override val parallelism: Int = 1
    override val scheduler: Scheduler = Schedulers.immediate()

    override fun ProcessingLifecycleExchange.toGroupKey(): Int = 0

    override fun handleExchange(exchange: ProcessingLifecycleExchange): Mono<Void> = Mono.empty()
}

private data class ProcessingLifecycleMessage(
    override val id: String = "message-id",
    override val header: Header = DefaultHeader.empty(),
    override val body: String = "body",
    override val createTime: Long = 1,
    override val aggregateName: String = "processing_lifecycle",
    override val contextName: String = "wow-core-test",
) : Message<ProcessingLifecycleMessage, String>,
    NamedAggregate

private class ProcessingLifecycleExchange(
    override val message: ProcessingLifecycleMessage = ProcessingLifecycleMessage(),
) : MessageExchange<ProcessingLifecycleExchange, ProcessingLifecycleMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
}
