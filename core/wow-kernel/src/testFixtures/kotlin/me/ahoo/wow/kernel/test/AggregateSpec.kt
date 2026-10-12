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

package me.ahoo.wow.kernel.test

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord

/**
 * Specifies an aggregate's behaviour as given events, a command and the expected outcome, against the real kernel
 * and an in-memory log:
 *
 * ```kotlin
 * aggregateSpec(cartModel) {
 *     given(CartCreated("ahoo"))
 *     whenCommand(AddItem("apple", 1)) {
 *         expectEvents(ItemAdded("apple", 1))
 *         expectState { items.assert().containsEntry("apple", 1) }
 *     }
 * }
 * ```
 *
 * The kernel checks that deciding never changes the state (F4): a decide function or after hook that assigns state
 * fails the specification.
 */
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    tenantId: String = "tenant",
    block: AggregateSpec<S>.() -> Unit,
) {
    AggregateSpec(model, model.type.ref(tenantId, id)).block()
}

class AggregateSpec<S : Any>(private val model: AggregateModel<S>, val ref: AggregateRef) {
    private val codec = EventCodec()
    private val log = InMemoryEventLog(partitions = 1)
    private val kernel = Kernel(log, codec, verifyDecidePurity = true)
    private var sequence = 0

    /** Commits [events] as one stream, as if an earlier command had produced them. */
    fun given(vararg events: Any) {
        require(events.isNotEmpty()) { "given needs at least one event." }
        runBlocking {
            val version = log.read(ref).toList().size + 1
            val stream = EventStreamRecord(
                ref = ref,
                version = version,
                commandId = "given-$version",
                requestId = "given-$version",
                fingerprint = "given",
                commitTime = 0,
                events = events.map { codec.encode(model, it) },
            )
            check(log.append(stream) is AppendOutcome.Committed) { "Could not commit the given events." }
        }
    }

    /** Sends [command] through the kernel and checks its outcome in [expect]. */
    fun whenCommand(
        command: Any,
        requestId: String = "request-${++sequence}",
        expectedVersion: Int? = null,
        expect: Expectations<S>.() -> Unit,
    ) {
        val outcome = runBlocking {
            kernel.execute(model, Command("command-${++sequence}", requestId, ref, command, expectedVersion))
        }
        Expectations(model, codec, outcome).expect()
    }
}

class Expectations<S : Any>(
    private val model: AggregateModel<S>,
    private val codec: EventCodec,
    val outcome: CommandOutcome,
) {
    /** The command committed exactly [events], in order. */
    fun expectEvents(vararg events: Any) {
        val committed = committed().stream.events.map { codec.decode(model, it) }
        if (committed != events.toList()) {
            throw AssertionError("Expected events ${events.toList()} but got $committed.")
        }
    }

    /** The command committed events of exactly these types, in order. */
    fun expectEventTypes(vararg types: Class<*>) {
        val committed = committed().stream.events.map { codec.decode(model, it).javaClass }
        if (committed != types.toList()) {
            throw AssertionError("Expected event types ${types.map { it.simpleName }} but got ${committed.map { it.simpleName }}.")
        }
    }

    /** The command was accepted and changed nothing. */
    fun expectUnchanged() {
        if (outcome !is CommandOutcome.Unchanged) throw AssertionError("Expected no change but got $outcome.")
    }

    /** The command was rejected with [code]. */
    fun expectRejected(code: String) {
        val rejected = outcome as? CommandOutcome.Rejected ?: throw AssertionError("Expected a rejection but got $outcome.")
        if (rejected.rejection.code != code) {
            throw AssertionError("Expected rejection $code but got ${rejected.rejection}.")
        }
    }

    /** Checks the state after the committed events. */
    fun expectState(check: S.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        (committed().state as S).check()
    }

    private fun committed(): CommandOutcome.Committed =
        outcome as? CommandOutcome.Committed ?: throw AssertionError("Expected committed events but got $outcome.")
}
