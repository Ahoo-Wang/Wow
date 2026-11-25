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

package me.ahoo.wow.spi.call

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Misuse
import org.junit.jupiter.api.Test

/**
 * The request ids of the calls a decision sends while deciding (decision D314 ④, as #6734 amended it): derived from
 * the command, the same in every attempt of an execution, and a call decided again must be the call that first took
 * its index.
 */
class DecidingCommandTest {
    data class Reserve(val sku: String, val quantity: Int)

    /** A body that is no data class: two of the same fields are not equal, and are written as the same JSON. */
    class Note(val text: String)

    private val ref = AggregateType("shop", "order").ref("t", "o-1")
    private val stock = AggregateType("stock", "item")

    private fun deciding() = DecidingCommand(ref, "r-1")

    private fun reserve(quantity: Int = 1) = CommandRequest.of(Reserve("sku-1", quantity), "i-1", stock)

    @Test
    fun `each attempt takes the indices from 0 again, and an id of its own takes none`() {
        val deciding = deciding()
        val first = listOf(deciding.nextRequestId(reserve()), deciding.nextRequestId(reserve(2)))
        deciding.nextAttempt()
        val second = listOf(deciding.nextRequestId(reserve()), deciding.nextRequestId(reserve(2)))

        first.assert().isEqualTo(second)
        first.assert().containsExactly(
            DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 0),
            DecidingCommand.requestIdOf(ref.type, ref.tenantId, "r-1", 1),
        )
        runBlocking {
            withContext(deciding) {
                deciding.nextAttempt()
                CommandRequest.of(Reserve("sku-1", 9), "i-1", stock, requestId = "own").let { requestIdOf(it) }
                    .assert().isEqualTo("own")
                requestIdOf(reserve()).assert().isEqualTo(first[0])
            }
        }
    }

    @Test
    fun `every execution of a request derives the same request ids, whatever aggregate id it was given`() {
        // Admission gives each attempt of a creation that names no aggregate a new id: the derivation leaves it out.
        val first = deciding()
        val retried = DecidingCommand(ref.type.ref(ref.tenantId, "o-2"), "r-1")
        listOf(retried.nextRequestId(reserve()), retried.nextRequestId(reserve(2))).assert()
            .containsExactly(first.nextRequestId(reserve()), first.nextRequestId(reserve(2)))
        // Another request, tenant or aggregate type is another request.
        val id = deciding().nextRequestId(reserve())
        DecidingCommand(ref, "r-2").nextRequestId(reserve()).assert().isNotEqualTo(id)
        DecidingCommand(ref.type.ref("t2", "o-1"), "r-1").nextRequestId(reserve()).assert().isNotEqualTo(id)
        DecidingCommand(AggregateType("shop", "cart").ref("t", "o-1"), "r-1").nextRequestId(reserve()).assert()
            .isNotEqualTo(id)
    }

    @Test
    fun `a call decided again with the same body written alike is the same call`() {
        val deciding = deciding()
        val note = CommandRequest.of(Note("hello"), "i-1", stock)
        val id = deciding.nextRequestId(note)
        deciding.nextAttempt()
        deciding.nextRequestId(CommandRequest.of(Note("hello"), "i-1", stock)).assert().isEqualTo(id)
    }

    @Test
    fun `a call decided again to another target, of another type or with another body fails, naming both`() {
        val note = CommandRequest.of(Note("1"), "i-1", stock)
        listOf(
            reserve() to reserve(2),
            reserve() to CommandRequest.of(Note("1"), "i-1", stock),
            reserve() to CommandRequest.of(Reserve("sku-1", 1), "i-2", stock),
            reserve() to CommandRequest.of(Reserve("sku-1", 1), "i-1", AggregateType("stock", "lot")),
            reserve() to CommandRequest.of(Reserve("sku-1", 1), "i-1", stock, targetScope = TargetScope.of("other")),
            reserve() to CommandRequest.of(Reserve("sku-1", 1), "i-1", stock, targetScope = TargetScope.of(scope = Scope("bob", null))),
            note to CommandRequest.of(Note("2"), "i-1", stock),
            // A target named by its type alone (a creation), by its id alone, or by neither (both from the class).
            reserve() to CommandRequest.of(Reserve("sku-1", 1), type = stock),
            CommandRequest.of(Reserve("sku-1", 1), "i-1") to CommandRequest.of(Reserve("sku-1", 2), "i-1"),
            reserve() to CommandRequest.of(Reserve("sku-1", 1)),
        ).forEach { (first, changed) ->
            val deciding = deciding()
            deciding.nextRequestId(first)
            deciding.nextAttempt()
            val failure = runCatching { deciding.nextRequestId(changed) }.exceptionOrNull()
            failure.assert().describedAs("$changed").isInstanceOf(Misuse.State::class.java)
            failure!!.message.assert().contains("shop.order", "r-1", "call #1", "attemptKey()")
        }
    }

    @Test
    fun `a call an earlier attempt did not make takes its index as the first one`() {
        val deciding = deciding()
        deciding.nextRequestId(reserve())
        deciding.nextAttempt()
        val first = deciding.nextRequestId(reserve())
        val added = deciding.nextRequestId(CommandRequest.of(Note("new"), "i-1", stock))
        deciding.nextAttempt()
        deciding.nextRequestId(reserve()).assert().isEqualTo(first)
        deciding.nextRequestId(CommandRequest.of(Note("new"), "i-1", stock)).assert().isEqualTo(added)
    }

    @Test
    fun `outside a decision a client chooses the request id itself`(): Unit = runBlocking {
        requestIdOf(reserve()).assert().isNull()
        requestIdOf(CommandRequest.of(Reserve("sku-1", 1), "i-1", stock, requestId = "own")).assert().isEqualTo("own")
    }

    private suspend fun requestIdOf(request: CommandRequest<*>): String? =
        currentCoroutineContext().requestIdOf(request)
}
