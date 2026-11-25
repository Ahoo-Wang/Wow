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

package me.ahoo.wow.client

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.spi.wire.CallWires
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the HTTP client sends of a request's target (D483 ⑤): a scope it names replaces the caller's, a tenant alone
 * keeps the caller's own scope, and a target tenant that is the caller's own targets nothing.
 */
class HttpCommandTargetsTest {
    data class DeliverItems(val items: List<String>)

    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val alice = Caller.of(tenantId = "t1", scope = Scope("alice"), subject = "alice", crossScope = true)

    private fun client(mode: ClientMode) = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
        .apply {
            when (mode) {
                is ClientMode.Service -> service(checkNotNull(mode.service))
                is ClientMode.User -> user(mode.credential)
            }
        }
        .retry(RetryPolicy.of(backoff = 1.milliseconds))
        .contracts(contractOf(BOX, DeliverItems::class.java))
        .build()
        .commands
        .of(alice)

    private fun request(target: TargetScope) =
        CommandRequest.builder(DeliverItems(emptyList())).aggregateId("x").targetScope(target).build()

    private fun userMode(target: TargetScope): Map<String, String> {
        peer.reply(Reply.json(resultOf(BOX.ref("t1", "x"))))
        runBlocking {
            withContext(ForwardedHeaders(mapOf("Authorization" to "Bearer user"))) {
                client(ClientMode.User(UserCredential.FORWARDED)).wait(request(target))
            }
        }
        return peer.taken.last().headers
    }

    @Test
    fun `a user-mode target naming only a scope should write it, in the caller's own tenant`() {
        userMode(TargetScope.of(scope = Scope("bob")))[WowHeaders.SCOPE].assert().isEqualTo("owner=\"bob\"")
    }

    @Test
    fun `a user-mode target naming the caller's own tenant alone should target nothing`() {
        userMode(TargetScope.of("t1")).containsKey(WowHeaders.SCOPE).assert().isFalse()
    }

    @Test
    fun `a service-mode target naming only a scope should assert it as the root's, acting elsewhere`(): Unit =
        runBlocking {
            peer.reply(Reply.json(resultOf(BOX.ref("t1", "x"))))
            client(ClientMode.Service(SERVICE)).wait(request(TargetScope.of(scope = Scope("bob"))))
            val root = CallWires.decode(peer.taken.single().headers.getValue(WowHeaders.CALL))
            root.tenantId.assert().isEqualTo("t1")
            Scope(root.ownerId, root.spaceId).assert().isEqualTo(Scope("bob"))
            root.operator!!.actedElsewhere.assert().isTrue()
        }

    private companion object {
        const val SERVICE = "basket-service"
    }
}
