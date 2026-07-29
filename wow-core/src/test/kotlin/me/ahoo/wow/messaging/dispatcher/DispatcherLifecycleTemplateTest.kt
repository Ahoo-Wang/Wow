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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.dispatcher.CompositeEventDispatcher
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Modifier
import java.time.Duration

class DispatcherLifecycleTemplateTest {

    @Test
    fun `dispatcher lifecycle templates are final`() {
        listOf(
            MainDispatcher::class.java,
            AggregateDispatcher::class.java,
            CompositeEventDispatcher::class.java,
        ).forEach { dispatcherType ->
            assertFinalLifecycleTemplate(dispatcherType)
        }
    }

    private fun assertFinalLifecycleTemplate(dispatcherType: Class<*>) {
        listOf(
            dispatcherType.getMethod("prepare", RuntimeContext::class.java),
            dispatcherType.getMethod("start"),
            dispatcherType.getMethod("quiesce"),
            dispatcherType.getMethod("stopGracefully"),
            dispatcherType.getMethod("forceStop"),
        ).forEach { method ->
            Modifier.isFinal(method.modifiers).assert().isTrue()
        }
    }

    @Test
    fun `duplicate component identity fails before dispatcher initialization`() {
        val dispatcher = SingleChildParentDispatcher(
            child = RecordingChild("child", mutableListOf()),
        )

        assertThrows<IllegalArgumentException> {
            WowRuntime(
                components = listOf(dispatcher, dispatcher),
                shutdownTimeout = Duration.ofSeconds(1),
                shutdownQuietPeriod = Duration.ZERO,
            )
        }

        val owner = newRuntime(dispatcher)
        owner.start().block()
        owner.forceStop()
    }

    @Test
    fun `parent rollback only cleans children whose preparation was attempted`() {
        val calls = mutableListOf<String>()
        val prepareFailure = IllegalStateException("child-prepare")
        val children = listOf(
            RecordingChild("first", calls),
            RecordingChild("second", calls, prepareFailure),
            RecordingChild("third", calls),
        )
        val runtime = newRuntime(MultiChildParentDispatcher(children))

        assertThrows<IllegalStateException> {
            runtime.start().block()
        }
            .assert()
            .isSameAs(prepareFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
            "quiesce:first",
            "quiesce:second",
            "stop:second",
            "stop:first",
        )
    }

    private fun newRuntime(dispatcher: RuntimeComponent): WowRuntime =
        WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

    private class SingleChildParentDispatcher(
        private val child: MessageDispatcher,
    ) : MainDispatcher<String>() {
        override val name: String = "hooked-parent"
        override val namedAggregates: Set<NamedAggregate> =
            setOf("runtime-template.child".toNamedAggregate().materialize())

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> =
            Flux.empty()

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>,
        ): MessageDispatcher = child
    }

    private class MultiChildParentDispatcher(
        children: List<MessageDispatcher>,
    ) : MainDispatcher<String>() {
        private val childrenByAggregate = children.mapIndexed { index, child ->
            "runtime-template.child-$index".toNamedAggregate().materialize() to child
        }.toMap()

        override val name: String = "multi-parent"
        override val namedAggregates: Set<NamedAggregate> = childrenByAggregate.keys

        override fun receiveMessage(subscription: MessageSubscription): Flux<String> =
            Flux.empty()

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>,
        ): MessageDispatcher = checkNotNull(childrenByAggregate[namedAggregate])
    }

    private class RecordingChild(
        override val name: String,
        private val calls: MutableList<String>,
        private val prepareFailure: RuntimeException? = null,
    ) : MessageDispatcher {
        override fun prepare(runtimeContext: RuntimeContext) {
            calls += "prepare:$name"
            prepareFailure?.let { throw it }
        }

        override fun start() {
            calls += "start:$name"
        }

        override fun quiesce() {
            calls += "quiesce:$name"
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                calls += "stop:$name"
            }

        override fun forceStop() {
            calls += "force:$name"
        }
    }
}
