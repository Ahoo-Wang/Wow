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
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.wire.CallWires
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Commands named by their type, to a peer described by its Manifest alone (decision D257). */
class NamedCommandTest {
    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "b-1")
    private val alice = Caller.of(tenantId = "t1", subject = "alice")

    private fun client(
        mode: ClientMode = ClientMode.Service(
            SERVICE
        ),
        described: Set<String> = setOf("mail")
    ) =
        WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .apply {
                when (mode) {
                    is ClientMode.Service -> service(checkNotNull(mode.service))
                    is ClientMode.User -> user(mode.credential)
                }
            }
            .contracts(contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java))
            .manifestPeers(described)
            .build()
            .commands

    private fun deliver(items: String = "mug") =
        NamedCommand("mail.box.DeliverItems", WowJson.mapper.readTree("""{"items":["$items"]}"""))

    @Test
    fun `a declared command should go to its route as the caller, its body as is`(): Unit = runBlocking {
        peer.reply(Reply.json(ManifestTree.write(MANIFEST)), Reply.json(resultOf(ref)))
        val result = client().of(alice).wait(CommandRequest.builder(ref, deliver()).wait(WaitPlan.DEFAULT).build())
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val (manifest, command) = peer.taken
        manifest.method.assert().isEqualTo("GET")
        manifest.path.assert().isEqualTo("/wow/manifest")
        CallWires.decode(manifest.headers.getValue(WowHeaders.CALL)).operator?.id.assert().isEqualTo("alice")
        command.path.assert().isEqualTo("/mail/box/b-1/deliver-items")
        command.body.assert().isEqualTo("""{"items":["mug"]}""")
        CallWires.decode(command.headers.getValue(WowHeaders.CALL)).operator?.id.assert().isEqualTo("alice")
    }

    @Test
    fun `the Manifest should be kept, and an alias should name its command`(): Unit = runBlocking {
        peer.reply(Reply.json(ManifestTree.write(MANIFEST)), Reply.json(resultOf(ref)), Reply.json(resultOf(ref)))
        val commands = client().of(alice)
        commands.wait(CommandRequest.to(ref, deliver()))
        commands.wait(CommandRequest.to(ref, NamedCommand("mail.box.Deliver", WowJson.mapper.readTree("{}"))))
        peer.taken.map { it.path }.assert().containsExactly(
            "/wow/manifest",
            "/mail/box/b-1/deliver-items",
            "/mail/box/b-1/deliver-items",
        )
    }

    @Test
    fun `an undeclared command should fail before it is sent, the Manifest read again once`(): Unit = runBlocking {
        peer.reply(
            Reply.json(ManifestTree.write(MANIFEST)),
            Reply.json(resultOf(ref)),
            Reply.json(ManifestTree.write(MANIFEST))
        )
        val commands = client().of(alice)
        commands.wait(CommandRequest.to(ref, deliver()))
        assertThrownBy<IllegalArgumentException> {
            runBlocking {
                commands.wait(
                    CommandRequest.to(ref, NamedCommand("mail.box.Burn", WowJson.mapper.readTree("{}")))
                )
            }
        }.hasMessageContaining("does not declare the command Burn")
        peer.taken.map { it.path }.assert().containsExactly(
            "/wow/manifest",
            "/mail/box/b-1/deliver-items",
            "/wow/manifest",
        )
    }

    @Test
    fun `a user-mode command should forward the end user's credential, and its refusal should pass through`(): Unit =
        runBlocking {
            val rejected = resultOf(
                ref,
                CommandResult.Status.REJECTED,
                ErrorInfo("wow.auth.forbidden", "alice may not.")
            )
            peer.reply(
                Reply.json(ManifestTree.write(MANIFEST)),
                Reply.problem(
                    403,
                    mapOf("code" to "wow.auth.forbidden", "detail" to "alice may not.", "result" to rejected)
                ),
            )
            val exception = assertThrownBy<CommandRejectedException> {
                runBlocking(ForwardedHeaders(mapOf("Authorization" to "Bearer alice"))) {
                    client(ClientMode.User(UserCredential.FORWARDED)).of(alice).wait(CommandRequest.to(ref, deliver()))
                }
            }.actual()
            exception.code.assert().isEqualTo("wow.auth.forbidden")
            peer.taken.forEach { it.headers["Authorization"].assert().isEqualTo("Bearer alice") }
            peer.taken.forEach { it.headers.containsKey(WowHeaders.CALL).assert().isFalse() }
        }

    @Test
    fun `a named command should go only to a peer described by its Manifest, and only to its own aggregate`(): Unit =
        runBlocking {
            assertThrownBy<IllegalArgumentException> {
                runBlocking { client(described = setOf("other")).wait(CommandRequest.to(ref, deliver())) }
            }.hasMessageContaining("not described by its Manifest")
            assertThrownBy<IllegalArgumentException> {
                runBlocking {
                    client().wait(
                        CommandRequest.to(ref, NamedCommand("mail.drawer.DeliverItems", WowJson.mapper.readTree("{}")))
                    )
                }
            }.hasMessageContaining("is not one of mail.box")
            peer.taken.assert().isEmpty()
        }

    @Test
    fun `the Manifest of a peer described by it should be read as the caller and kept`(): Unit = runBlocking {
        peer.reply(Reply.json(ManifestTree.write(MANIFEST)))
        val client = WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .service(SERVICE)
            .contracts(contractOf(BOX, HttpCommandClientTest.DeliverItems::class.java))
            .manifestPeers(setOf("mail"))
            .build()
        client.manifestOf("mail", alice).assert().isEqualTo(MANIFEST)
        client.manifestOf("mail", alice).assert().isEqualTo(MANIFEST)
        peer.taken.single().path.assert().isEqualTo("/wow/manifest")
        CallWires.decode(
            peer.taken.single().headers.getValue(WowHeaders.CALL)
        ).operator?.id.assert().isEqualTo("alice")
        assertThrownBy<IllegalArgumentException> { runBlocking { client.manifestOf("other", alice) } }
            .hasMessageContaining("not described by its Manifest")
        val plain = WowClient.builder().endpoints(EndpointResolver.NONE).build()
        assertThrownBy<IllegalArgumentException> { runBlocking { plain.manifestOf("mail", alice) } }
            .hasMessageContaining("not described by its Manifest")
    }

    @Test
    fun `a peer's Manifest that cannot be read should fail the send`(): Unit = runBlocking {
        peer.reply(Reply.problem(403, mapOf("code" to "wow.auth.forbidden", "detail" to "No.")))
        assertThrownBy<me.ahoo.wow.api.problem.WowProblemException> {
            runBlocking { client().of(alice).wait(CommandRequest.to(ref, deliver())) }
        }.hasMessageContaining("The Manifest of mail")
    }

    @Test
    fun `a command object to a peer described by its Manifest should be a configuration error`(): Unit = runBlocking {
        assertThrownBy<IllegalStateException> {
            runBlocking { client().wait(CommandRequest.to(ref, HttpCommandClientTest.DeliverItems(listOf("mug")))) }
        }.hasMessageContaining("send it a NamedCommand")
    }

    @Test
    fun `a named command should have a full type, and equal another of its type and body`() {
        assertThrownBy<IllegalArgumentException> { NamedCommand("box.Deliver", WowJson.mapper.readTree("{}")) }
        val command = deliver()
        command.name.assert().isEqualTo("DeliverItems")
        command.target.assert().isEqualTo("mail.box")
        command.assert().isEqualTo(deliver())
        command.hashCode().assert().isEqualTo(deliver().hashCode())
        command.assert().isNotEqualTo(deliver("cup"))
        command.toString().assert().contains("mail.box.DeliverItems")
    }

    private companion object {
        const val SERVICE = "basket-service"

        val MANIFEST = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "mail",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "box",
                            state = StateManifest("#/\$defs/mail.BoxState"),
                            commands = listOf(
                                CommandManifest(
                                    name = "DeliverItems",
                                    aliases = listOf("Deliver"),
                                    schema = "#/\$defs/mail.DeliverItems",
                                    routes = CommandRoutes.of("mail", "box", "DeliverItems", Creation.NONE),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
