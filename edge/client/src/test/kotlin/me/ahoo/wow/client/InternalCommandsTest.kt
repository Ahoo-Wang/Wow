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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A peer's internal command (`@InternalCommand`), which its contract marks: a service's alone to send (X-64). */
class InternalCommandsTest {
    data class Seal(val reason: String)

    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val contract = ManifestFragment(
        "mail-api.jar",
        Manifest(
            contexts = listOf(
                ContextManifest(
                    "mail",
                    aggregates = listOf(
                        AggregateManifest(
                            "box",
                            state = StateManifest(Manifest.schemaRef("BoxState")),
                            commands = listOf(
                                CommandManifest(
                                    "Seal",
                                    schema = Manifest.schemaRef(Seal::class.java.name),
                                    routes = CommandRoutes.of("mail", "box", "Seal", Creation.NONE),
                                    internal = true,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun client(mode: WowClient.Builder.() -> WowClient.Builder) = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
        .mode()
        .contracts(listOf(contract))
        .build()
        .commands
        .of(Caller.of(subject = "alice"))

    @Test
    fun `a user-mode client should refuse an internal command, naming it, before it is sent`() {
        assertThrownBy<Misuse.Argument> {
            runBlocking { client { user(UserCredential.NONE) }.send(CommandRequest.of(Seal("audit"), "b-1")) }
        }.hasMessageContaining("mail.box.Seal").hasMessageContaining("internal")
        peer.taken.assert().isEmpty()
    }

    @Test
    fun `a service should send an internal command to the peer's internal port`() {
        peer.reply(Reply.json(resultOf(BOX.ref("t1", "b-1"))))
        runBlocking { client { service("audit-service") }.wait(CommandRequest.of(Seal("audit"), "b-1")) }
        peer.taken.single().let {
            it.path.assert().isEqualTo("/mail/box/b-1/seal")
            it.headers[WowHeaders.SERVICE].assert().isEqualTo("audit-service")
        }
    }
}
