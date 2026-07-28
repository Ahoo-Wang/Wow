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
import me.ahoo.wow.runtime.RuntimeOwnershipClaim
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.lang.reflect.Modifier
import java.time.Duration

class DispatcherLifecycleTemplateTest {

    @Test
    fun `dispatcher lifecycle templates are final and managed hooks remain protected`() {
        listOf(
            MainDispatcher::class.java,
            AggregateDispatcher::class.java,
            CompositeEventDispatcher::class.java,
        ).forEach { dispatcherType ->
            assertFinalLifecycleTemplate(dispatcherType)
            assertProtectedManagedHooks(dispatcherType)
        }
    }

    private fun assertFinalLifecycleTemplate(dispatcherType: Class<*>) {
        listOf(
            dispatcherType.getMethod("claimRuntimeOwnership"),
            dispatcherType.getMethod("prepare", RuntimeContext::class.java),
            dispatcherType.getMethod("start"),
            dispatcherType.getMethod("stopGracefully"),
            dispatcherType.getMethod("forceStop"),
        ).forEach { method ->
            Modifier.isFinal(method.modifiers).assert().isTrue()
        }
    }

    private fun assertProtectedManagedHooks(dispatcherType: Class<*>) {
        listOf(
            dispatcherType.getDeclaredMethod("prepareManaged", RuntimeContext::class.java),
            dispatcherType.getDeclaredMethod("startManaged"),
            dispatcherType.getDeclaredMethod("stopManagedGracefully"),
            dispatcherType.getDeclaredMethod("forceStopManaged"),
        ).forEach { method ->
            Modifier.isProtected(method.modifiers).assert().isTrue()
            Modifier.isFinal(method.modifiers).assert().isFalse()
        }
    }

    @Test
    fun `managed hooks cannot replace parent dispatcher lifecycle invariants`() {
        val calls = mutableListOf<String>()
        val child = RecordingChild("child", calls)
        val dispatcher = HookedParentDispatcher(child, calls)
        val runtime = newRuntime(dispatcher)

        runtime.start()
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()

        calls.assert().containsExactly(
            "prepare:child",
            "hook:prepare",
            "start:child",
            "hook:start",
            "stop:child",
            "hook:stop",
        )
    }

    @Test
    fun `managed force hook cannot replace child force stop`() {
        val calls = mutableListOf<String>()
        val child = RecordingChild("child", calls)
        val dispatcher = HookedParentDispatcher(child, calls)
        val runtime = newRuntime(dispatcher)
        runtime.start()
        calls.clear()

        runtime.forceStop()

        calls.assert().containsExactly(
            "force:child",
            "hook:force",
        )
    }

    @Test
    fun `a second outer runtime cannot claim dispatcher ownership`() {
        val dispatcher = HookedParentDispatcher(
            child = RecordingChild("child", mutableListOf()),
            calls = mutableListOf(),
        )
        val runtime = newRuntime(dispatcher)

        assertThrows<IllegalStateException> {
            newRuntime(dispatcher)
        }
            .message
            .assert()
            .contains("already EXTERNAL")

        runtime.forceStop()
    }

    @Test
    fun `public dispatcher lifecycle cannot bypass an outer runtime owner`() {
        val dispatcher = HookedParentDispatcher(
            child = RecordingChild("child", mutableListOf()),
            calls = mutableListOf(),
        )
        val runtime = newRuntime(dispatcher)

        assertThrows<IllegalStateException>(dispatcher::start)
            .message
            .assert()
            .contains("owned by an external WowRuntime")

        runtime.start()
        runtime.forceStop()
    }

    @Test
    fun `failed multi-component claim rolls earlier dispatcher ownership back`() {
        val first = HookedParentDispatcher(
            child = RecordingChild("first-child", mutableListOf()),
            calls = mutableListOf(),
        )
        val second = HookedParentDispatcher(
            child = RecordingChild("second-child", mutableListOf()),
            calls = mutableListOf(),
        )
        val secondOwner = newRuntime(second)

        assertThrows<IllegalStateException> {
            WowRuntime(
                components = listOf(first, second),
                shutdownTimeout = Duration.ofSeconds(1),
                shutdownQuietPeriod = Duration.ZERO,
            )
        }

        val firstOwner = newRuntime(first)
        firstOwner.start()
        firstOwner.forceStop()
        secondOwner.forceStop()
    }

    @Test
    fun `duplicate component identity fails before dispatcher ownership is claimed`() {
        val dispatcher = HookedParentDispatcher(
            child = RecordingChild("child", mutableListOf()),
            calls = mutableListOf(),
        )

        assertThrows<IllegalArgumentException> {
            WowRuntime(
                components = listOf(dispatcher, dispatcher),
                shutdownTimeout = Duration.ofSeconds(1),
                shutdownQuietPeriod = Duration.ZERO,
            )
        }

        val owner = newRuntime(dispatcher)
        owner.start()
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

        assertThrows<IllegalStateException>(runtime::start)
            .assert()
            .isSameAs(prepareFailure)
        calls.assert().containsExactly(
            "prepare:first",
            "prepare:second",
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

    private class HookedParentDispatcher(
        private val child: MessageDispatcher,
        private val calls: MutableList<String>,
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

        override fun prepareManaged(runtimeContext: RuntimeContext) {
            calls += "hook:prepare"
        }

        override fun startManaged() {
            calls += "hook:start"
        }

        override fun stopManagedGracefully(): Mono<Void> =
            Mono.fromRunnable {
                calls += "hook:stop"
            }

        override fun forceStopManaged() {
            calls += "hook:force"
        }
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
    ) : MessageDispatcher,
        RuntimeComponent {
        override fun claimRuntimeOwnership(): RuntimeOwnershipClaim =
            RuntimeOwnershipClaim.shared(this)

        override fun prepare(runtimeContext: RuntimeContext) {
            calls += "prepare:$name"
            prepareFailure?.let { throw it }
        }

        override fun start() {
            calls += "start:$name"
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
