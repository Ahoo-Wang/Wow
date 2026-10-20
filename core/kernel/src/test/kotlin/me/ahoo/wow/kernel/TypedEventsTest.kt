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

package me.ahoo.wow.kernel

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ProjectContext
import me.ahoo.wow.api.subscribe.ReactContext
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.api.subscribe.SubscriptionContext
import org.junit.jupiter.api.Test

/**
 * Decision D264: a handler of each event of a type is called once per such event of a stream, in order, all calls on
 * the stream's one context, so the commands they derive and the calls they make are numbered across the stream.
 */
class TypedEventsTest {
    data class Placed(val item: String)

    data class Noted(val note: String)

    private val order = AggregateType("shop", "order")
    private val inventory = AggregateType("inventory", "stock")

    private val stream = StateEvent(
        ref = order.ref("t", "o-1"),
        version = 4,
        commandId = "c-4",
        requestId = "r-4",
        commandType = "shop.order.Place",
        commitTime = 1,
        events = listOf(Placed("mug"), Noted("n"), Placed("hat")),
        state = "state",
    )

    @Test
    fun `typed views should name their event and be equal per stream and index`() {
        val first = StateTypedEvent.of<String, Placed>(stream, 0)
        first.event.assert().isEqualTo(Placed("mug"))
        first.eventIndex.assert().isZero()
        first.id.assert().isEqualTo(stream.id)
        first.correlationId.assert().isEqualTo("c-4")
        first.state.assert().isEqualTo("state")
        first.assert().isEqualTo(StateTypedEvent.of<String, Placed>(stream, 0))
        first.hashCode().assert().isEqualTo(StateTypedEvent.of<String, Placed>(stream, 0).hashCode())
        first.assert().isNotEqualTo(StateTypedEvent.of<String, Placed>(stream, 2))
        first.assert().isNotEqualTo(stream)
        first.toString().assert().startsWith("StateTypedEvent(eventIndex=0, event=Placed(item=mug)")
        // A copy of a view is a whole stream again.
        first.copyWith(version = 5).version.assert().isEqualTo(5)
        assertThrownBy<IllegalArgumentException> { StateTypedEvent.of<String, Placed>(stream, 3) }
    }

    @Test
    fun `each event of the type should be handed over in order, keyed per call`(): Unit = runBlocking {
        val keys = mutableListOf<String>()
        val context = contextOf(FunctionKind.REACT, "Auditor.onPlaced", stream)
        val items = eachEvent(stream, Placed::class.java, context) { typed, invocation ->
            keys += invocation.invocationKey
            invocation.executionId.assert().isEqualTo(context.executionId)
            typed.event.item
        }
        items.assert().containsExactly("mug", "hat")
        keys.assert().containsExactly(
            "Auditor.onPlaced:shop.order/t/o-1@4:0",
            "Auditor.onPlaced:shop.order/t/o-1@4:2",
        )
        context.invocationKey.assert().isEqualTo("Auditor.onPlaced:shop.order/t/o-1@4")
        // Each kind of context keeps its kind.
        (invocationOf(context, 1) is ReactContext).assert().isTrue()
        (invocationOf(contextOf(FunctionKind.PROJECT, "Board.onPlaced", stream), 1) is ProjectContext).assert()
            .isTrue()
        val plain = object : SubscriptionContext {
            override val executionId = context.executionId
        }
        invocationOf<SubscriptionContext>(plain, 2).invocationKey.assert().isEqualTo("Auditor.onPlaced:shop.order/t/o-1@4:2")
        eachEvent(stream, Long::class.javaObjectType, context) { _, _ -> error("no event is a Long") }.assert()
            .isEmpty()
    }

    /**
     * The adversarial case of decision D264: a saga's handler of each `Placed` calls the inventory synchronously and
     * derives a command, and fails on the second `Placed` after the first call went out. The stream is handled again.
     * What the first attempt derived was never returned, so never executed; what it called is called again with the
     * same request, which the target answers once (D191); the retry derives the very commands, ids included, that a
     * first attempt that had not failed would have.
     */
    @Test
    fun `a stream handled again after a failure midway should repeat the same requests`(): Unit = runBlocking {
        val called = mutableListOf<Command<*>>()
        var failOn: String? = "hat"
        val binding = ProcessorBinding.saga(
            "OrderSaga",
            "onPlaced",
            String::class.java,
            eventType = Placed::class.java,
        ) { event, context ->
            eachEvent(event, Placed::class.java, context) { typed, invocation ->
                check(typed.event.item != failOn) { "The gateway is down." }
                invocation.call(invocation.command(inventory.ref("t", typed.event.item), "reserve"))
                derivedOf(invocation.command(typed.ref, "confirm ${typed.event.item}"))
            }.flatten()
        }
        val succeeded = mockk<CommandResult> { every { status } returns CommandResult.Status.SUCCEEDED }
        fun context() = contextOf(
            FunctionKind.SAGA,
            binding.name,
            stream,
            calls = CommandCalls {
                called += it
                succeeded
            },
        ) as SagaContext

        assertThrownBy<IllegalStateException> { runBlocking { binding.invoke(stream, context()) } }
        val firstAttempt = called.map { it.requestId }
        failOn = null
        val derived = binding.invoke(stream, context())
        // The first call is made again with its request id, then the second one's.
        called.map { it.requestId }.assert().isEqualTo(firstAttempt + called.drop(1).map { it.requestId })
        called[0].requestId.assert().isEqualTo(called[1].requestId)
        // The commands are numbered across the stream: call, command, call, command.
        derived.map { it.id }.assert().containsExactly(
            ExecutionSagaContext.derivedId(context().executionId, 1),
            ExecutionSagaContext.derivedId(context().executionId, 3),
        )
        val again = binding.invoke(stream, context())
        again.map { it.requestId }.assert().isEqualTo(derived.map { it.requestId })
        binding.skips(listOf(Noted("n"))).assert().isTrue()
        binding.skips(stream.events).assert().isFalse()
    }
}
