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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.ForwardedHeaders
import me.ahoo.wow.client.NamedCommand
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import me.ahoo.wow.http.internal.InternalServer
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * A user's command through `wow-client` to an aggregate without snapshots, over a real server: the default plan is
 * the service's to resolve, to the commit there, so a request that names only a timeout must not ask for a snapshot.
 */
class UserCommandWaitTest {
    /** The cart, as a context that keeps no snapshots declares it; the node runs no snapshot subscription either. */
    private val manifest = cartManifest().let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { it.copyWith(snapshot = SnapshotManifest(false)) }
                )
            },
        )
    }
    private val node = HttpNode(manifest = manifest)
    private val server = InternalServer(node.routes)

    @AfterEach
    fun close() {
        server.close()
    }

    private val commands = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf(CONTEXT to URI.create("http://${server.address}"))))
        .user(UserCredential.FORWARDED)
        // The client reads the cart's Manifest from the node: it has no api jar of the cart.
        .manifestPeers(setOf(CONTEXT))
        .build()
        .commands
        .of(Caller.of(tenantId = TENANT, scope = Scope("alice"), subject = "alice"))

    /** An item for cart `c-1`, by its full type name, as a peer described by its Manifest is sent one. */
    private val addItem = NamedCommand("shop.cart.AddItem", WowJson.mapper.readTree("""{"sku":"apple"}"""))

    private suspend fun wait(request: CommandRequest<*>) =
        withContext(ForwardedHeaders(mapOf("X-User" to "alice"))) { commands.wait(request) }

    @Test
    fun `a command with only a timeout should wait for the commit where there is no snapshot`(): Unit = runBlocking {
        val result = wait(CommandRequest.of(addItem, id = "c-1", timeout = 5.seconds))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.stage.assert().isEqualTo(Stage.COMMITTED)
        result.ref.id.assert().isEqualTo("c-1")
    }

    @Test
    fun `a plan that asks for the snapshot explicitly should still be refused at admission`() {
        val refused = runCatching {
            runBlocking {
                wait(CommandRequest.of(addItem, id = "c-1", wait = WaitPlan.SNAPSHOT))
            }
        }.exceptionOrNull()
        (refused as CommandRejectedException).code.assert().isEqualTo(FrameworkCodes.WAIT_INVALID)
    }
}
