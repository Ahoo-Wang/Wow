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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.RebuildSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The system commands a peer's contract lists, sent by their classes as any other command (decision D389 F1). */
class SystemCommandsTest {
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
                            commands = Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle("mail", "box", it) } +
                                SystemCommands.rebuild("mail", "box"),
                        ),
                    ),
                    commands = SystemCommands.jobs("mail"),
                ),
            ),
        ),
    )

    private fun client() = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base)))
        .user(UserCredential.NONE)
        .contracts(listOf(contract))
        .build()
        .commands
        .of(Caller.ANONYMOUS)

    private fun sent(request: CommandRequest<*>): Taken {
        peer.reply(Reply.json(resultOf(BOX.ref("t1", "b-1"), hop = Hop.accepted("mail")), status = 202))
        runBlocking { client().send(request) }
        return peer.taken.last()
    }

    @Test
    fun `a lifecycle command should go to its route on the aggregate the request names`() {
        sent(CommandRequest.of(DeleteAggregate(), id = "b-1", type = BOX)).let {
            it.method.assert().isEqualTo("DELETE")
            it.path.assert().isEqualTo("/mail/box/b-1")
        }
        sent(CommandRequest.of(TransferOwner.of("bob"), id = "b-1", type = BOX)).let {
            it.method.assert().isEqualTo("POST")
            it.path.assert().isEqualTo("/mail/box/b-1/wow.lifecycle.transfer-owner")
            WowJson.mapper.readTree(it.body)["ownerId"].asString().assert().isEqualTo("bob")
        }
    }

    @Test
    fun `a snapshot rebuild should go to its aggregate type's route`() {
        sent(CommandRequest.of(RebuildSnapshot.of(listOf("b-1", "b-2")), type = BOX)).let {
            it.path.assert().isEqualTo("/mail/box/wow.snapshot.rebuild-snapshot")
            WowJson.mapper.readTree(it.body)["ids"].values().map { id -> id.asString() }
                .assert().containsExactly("b-1", "b-2")
        }
    }

    @Test
    fun `a job's command should go to the job its id names, on the context's job aggregate`() {
        val jobs = AggregateType("mail", ScheduleJobs.AGGREGATE)
        sent(CommandRequest.of(DisableJob(), id = "sweep", type = jobs)).path
            .assert().isEqualTo("/mail/wow.schedule/sweep/disable-job")
        sent(CommandRequest.of(EnableJob.of("sweep"), id = "sweep", type = jobs)).let {
            it.path.assert().isEqualTo("/mail/wow.schedule/sweep/enable-job")
            WowJson.mapper.readTree(it.body)["job"].asString().assert().isEqualTo("sweep")
        }
    }

    @Test
    fun `a system command the contract does not list on its target should be refused before it is sent`() {
        assertThrownBy<Misuse.Argument> {
            runBlocking { client().send(CommandRequest.of(DisableJob(), id = "sweep", type = BOX)) }
        }
        peer.taken.assert().isEmpty()
    }
}
