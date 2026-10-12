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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import org.junit.jupiter.api.Test

class KernelTest {
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    private fun command(body: Any, requestId: String = "request-${++sequence}", expectedVersion: Int? = null) =
        Command(
            id = "command-${++sequence}",
            requestId = requestId,
            ref = ref,
            body = body,
            expectedVersion = expectedVersion
        )

    @Test
    fun `create then add commits one stream per command and evolves the state`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        kernel.execute(
            cartModel,
            command(CreateCart("ahoo"))
        ).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        val added = kernel.execute(cartModel, command(AddItem("apple", 2))) as CommandOutcome.Committed
        added.stream.version.assert().isEqualTo(2)
        (added.state as CartState).items.assert().containsEntry("apple", 2)
        log.read(ref).toList().map { it.events.single().name }
            .assert().containsExactly("example.cart.CartCreated", "example.cart.ItemAdded")
    }

    @Test
    fun `a command that does not create is rejected when the aggregate does not exist`() = runTest {
        val outcome = Kernel(InMemoryEventLog()).execute(cartModel, command(AddItem("apple", 1)))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.NOT_FOUND)
    }

    @Test
    fun `a creating command is rejected when the aggregate exists`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        val outcome = kernel.execute(cartModel, command(CreateCart("other")))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.EXISTS)
    }

    @Test
    fun `a command allowed to create acts on a missing and on an existing aggregate`() = runTest {
        val upsert = aggregate<CartState>("example", "cart") {
            initial { CartState(it.id) }
            decide<AddItem>(Creation.ALLOWED) { _, command -> Decision.events(ItemAdded(command.productId, 1)) }
            evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
        }
        val kernel = Kernel(InMemoryEventLog())
        (
            kernel.execute(
                upsert,
                command(AddItem("apple", 1))
            ) as CommandOutcome.Committed
            ).stream.version.assert().isEqualTo(1)
        (
            kernel.execute(
                upsert,
                command(AddItem("apple", 1))
            ) as CommandOutcome.Committed
            ).stream.version.assert().isEqualTo(2)
    }

    @Test
    fun `an unhandled command type is rejected`() = runTest {
        val outcome = Kernel(InMemoryEventLog()).execute(cartModel, command("not a command"))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.UNHANDLED)
    }

    @Test
    fun `a rejection writes nothing`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        kernel.execute(cartModel, command(AddItem("a", 1)))
        kernel.execute(cartModel, command(AddItem("b", 1)))
        val outcome = kernel.execute(cartModel, command(AddItem("c", 1)))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("cart.full")
        log.read(ref).toList().size.assert().isEqualTo(3)
    }

    @Test
    fun `no events leave the aggregate unchanged`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        kernel.execute(cartModel, command(ClearCart("empty"))).assert().isEqualTo(CommandOutcome.Unchanged(1))
    }

    @Test
    fun `a suspending decide function runs like a plain one`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        kernel.execute(
            cartModel,
            command(CheckStock("apple"))
        ).assert().isInstanceOf(CommandOutcome.Committed::class.java)
    }

    @Test
    fun `an expected version that is not the current one is rejected`() = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        val outcome = kernel.execute(cartModel, command(AddItem("apple", 1), expectedVersion = 0))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.VERSION_MISMATCH)
    }

    @Test
    fun `a retried command is not decided again`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        val first = kernel.execute(
            cartModel,
            command(AddItem("apple", 1), requestId = "same")
        ) as CommandOutcome.Committed
        val retry = kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "same"))
        retry.assert().isEqualTo(CommandOutcome.AlreadyCommitted(first.stream))
        log.read(ref).toList().size.assert().isEqualTo(2)
    }

    @Test
    fun `a request id reused by a command of another type is rejected`(): Unit = runTest {
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "same"))
        val outcome = kernel.execute(cartModel, command(ClearCart("reused"), requestId = "same"))
        (outcome as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.REQUEST_ID_REUSED)
    }

    @Test
    fun `a request id reused by a command of the same type is taken for a retry`(): Unit = runTest {
        // Only the type name is compared (D55): the caller's contract is one request id per business request.
        val kernel = Kernel(InMemoryEventLog())
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        val first = kernel.execute(
            cartModel,
            command(AddItem("apple", 1), requestId = "same")
        ) as CommandOutcome.Committed
        kernel.execute(cartModel, command(AddItem("pear", 1), requestId = "same"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(first.stream))
    }

    @Test
    fun `the stream records the command's model name, causation and headers`(): Unit = runTest {
        val kernel = Kernel(InMemoryEventLog())
        val causation = Causation("root-command", "example.cart/tenant/other@2")
        val created = kernel.execute(
            cartModel,
            Command("c-1", "r-1", ref, CreateCart("ahoo"), headers = mapOf("app" to "console"), causation = causation),
        ) as CommandOutcome.Committed
        created.stream.commandType.assert().isEqualTo("example.cart.CreateCart")
        created.stream.causation.assert().isEqualTo(causation)
        created.stream.headers.assert().isEqualTo(mapOf("app" to "console"))
        val added = kernel.execute(cartModel, command(AddItem("apple", 1))) as CommandOutcome.Committed
        added.stream.causation.assert().isNull()
        added.stream.headers.assert().isEmpty()
    }

    @Test
    fun `a command named by the model keeps its name when its class is renamed`(): Unit = runTest {
        val named = aggregate<CartState>("example", "cart") {
            initial { CartState(it.id) }
            decide<AddItem>(Creation.ALLOWED, name = "example.cart.PutItem") { _, command ->
                Decision.events(ItemAdded(command.productId, 1))
            }
            evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
        }
        named.commandType(AddItem::class.java).name.assert().isEqualTo("example.cart.PutItem")
        val outcome = Kernel(
            InMemoryEventLog()
        ).execute(named, command(AddItem("apple", 1))) as CommandOutcome.Committed
        outcome.stream.commandType.assert().isEqualTo("example.cart.PutItem")
    }

    @Test
    fun `two command types of one aggregate cannot share a name`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<CartState>("example", "cart") {
                initial { CartState(it.id) }
                decide<AddItem>(name = "example.cart.Put") { _, _ -> Decision.NoOp }
                decide<ClearCart>(name = "example.cart.Put") { _, _ -> Decision.NoOp }
            }
        }
        assertThrownBy<IllegalArgumentException> { cartModel.commandType(String::class.java) }
    }

    @Test
    fun `a version conflict decides again on the newer state`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        // Another writer commits between this command's load and its append.
        val racing = RacingLog(log) { Kernel(log).execute(cartModel, command(AddItem("pear", 1))) }
        val outcome = Kernel(racing).execute(cartModel, command(AddItem("apple", 1))) as CommandOutcome.Committed
        outcome.stream.version.assert().isEqualTo(3)
        (outcome.state as CartState).items.keys.assert().containsExactlyInAnyOrder("pear", "apple")
    }

    @Test
    fun `losing every attempt reports contention`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(cartModel, command(CreateCart("ahoo")))
        val alwaysRacing = RacingLog(log, every = true) { Kernel(log).execute(cartModel, command(AddItem("pear", 1))) }
        Kernel(alwaysRacing, maxAttempts = 2).execute(cartModel, command(AddItem("apple", 1)))
            .assert().isEqualTo(CommandOutcome.Contended(2))
    }

    @Test
    fun `an unknown append that did commit is settled by the version slot`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(cartModel, command(CreateCart("ahoo")))
        val outcome = Kernel(UnknownLog(log, commit = true)).execute(cartModel, command(AddItem("apple", 1)))
        outcome.assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
    }

    @Test
    fun `an unknown append with an empty slot stays unknown`() = runTest {
        val log = InMemoryEventLog()
        Kernel(log).execute(cartModel, command(CreateCart("ahoo")))
        Kernel(UnknownLog(log, commit = false)).execute(cartModel, command(AddItem("apple", 1)))
            .assert().isInstanceOf(CommandOutcome.Unknown::class.java)
    }

    /** Runs [race] right before the first (or [every]) append. */
    private class RacingLog(
        private val log: EventLog,
        private val every: Boolean = false,
        private val race: suspend () -> Unit,
    ) : EventLog by log {
        private var raced = false

        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
            if (every || !raced) {
                raced = true
                race()
            }
            return log.append(stream, transport)
        }
    }

    /** Reports every append as unknown, after committing it when [commit]. */
    private class UnknownLog(private val log: EventLog, private val commit: Boolean) : EventLog by log {
        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
            if (commit) log.append(stream, transport)
            return AppendOutcome.Unknown(IllegalStateException("timeout"))
        }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            log.read(ref, fromVersion, toVersion)
    }
}
