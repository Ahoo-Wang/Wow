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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/**
 * The kernel's dedup pre-check looks the request id up in the command's aggregate type alone (decision D162). Without
 * recent requests (decision D261, `recentRequests = 0`) it looks up every command's.
 */
class KernelRequestLookupTest {
    private val wishlist = AggregateType(cartModel.type.context, "wishlist")
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    private fun command(body: Any, requestId: String = "request-${++sequence}") =
        Command(id = "command-${++sequence}", requestId = requestId, ref = ref, body = body)

    /** Records the request-id lookups the kernel asks of the log. */
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        val perType = mutableListOf<AggregateType>()
        var contextWide = 0

        override suspend fun findByRequestId(
            type: AggregateType,
            tenantId: String,
            requestId: String,
        ): EventStreamRecord? {
            perType += type
            return delegate.findByRequestId(type, tenantId, requestId)
        }

        override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? {
            contextWide++
            return delegate.findByRequestId(tenantId, requestId)
        }
    }

    @Test
    fun `each command looks its request id up once, in its own aggregate type`(): Unit = runTest {
        val log = CountingLog()
        val kernel = Kernel(log, recentRequests = 0)
        kernel.execute(
            cartModel,
            command(CreateCart("ahoo"))
        ).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        kernel.execute(
            cartModel,
            command(AddItem("apple", 1))
        ).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        log.perType.assert().containsExactly(cartModel.type, cartModel.type)
        log.contextWide.assert().isEqualTo(0)
    }

    @Test
    fun `a retry is found by the lookup in its aggregate type`(): Unit = runTest {
        val log = CountingLog()
        val kernel = Kernel(log, recentRequests = 0)
        kernel.execute(cartModel, command(CreateCart("ahoo")))
        val first = kernel.execute(
            cartModel,
            command(AddItem("apple", 1), requestId = "same")
        ) as CommandOutcome.Committed
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "same"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(first.stream))
        log.perType.assert().containsExactly(cartModel.type, cartModel.type, cartModel.type)
        log.contextWide.assert().isEqualTo(0)
    }

    @Test
    fun `a request id committed by another aggregate type of the context is not a duplicate`(): Unit = runTest {
        val log = CountingLog()
        val other = EventStreamRecord(
            ref = wishlist.ref("tenant", "wish-1"),
            version = 1,
            commandId = "command-0",
            requestId = "same",
            commandType = "example.wishlist.CreateWishlist",
            commitTime = 1L,
            events = listOf(EventRecord("example.wishlist.WishlistCreated", 1, "{}")),
            scope = Scope.NONE,
        )
        log.append(other).assert().isInstanceOf(AppendOutcome.Committed::class.java)
        val created = Kernel(log).execute(cartModel, command(CreateCart("ahoo"), requestId = "same"))
        (created as CommandOutcome.Committed).stream.ref.assert().isEqualTo(ref)
        log.findByRequestId(wishlist, "tenant", "same").assert().isEqualTo(other)
        log.findByRequestId(cartModel.type, "tenant", "same").assert().isEqualTo(created.stream)
    }
}
