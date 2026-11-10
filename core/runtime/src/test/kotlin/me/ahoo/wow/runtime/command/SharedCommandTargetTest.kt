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
package me.ahoo.wow.runtime.command

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.spi.transport.EnvelopeCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A command class two aggregates of another context decide, sent to either by naming it (issue #5475). */
class SharedCommandTargetTest {
    data class Refresh(val value: String)

    private val schema = Manifest.schemaRef(Refresh::class.java.name)
    private val peer = Manifest(
        contexts = listOf(
            ContextManifest(
                "peer",
                aggregates = listOf("alpha", "beta", "gamma").map { name ->
                    AggregateManifest(
                        name,
                        state = StateManifest(Manifest.schemaRef("audit.$name.State")),
                        commands = if (name == "gamma") {
                            emptyList()
                        } else {
                            listOf(
                                CommandManifest("Refresh", schema = schema)
                            )
                        },
                    )
                },
            ),
        ),
    )
    private val contracts = ContractDirectory(Manifest(), listOf(ManifestFragment("peer-api", peer)))
    private val topics = InMemoryTopics(partitions = 1)
    private val sender = CommandSender(contracts, SourceTopics(topics, "", topics))

    @Test
    fun `a command class two aggregates decide is sent to the one the command names, as its command`(): Unit =
        runBlocking {
            sender.send(Command("first", "first", AggregateType("peer", "alpha").ref("t", "a"), Refresh("ok")))
            sender.send(Command("second", "second", AggregateType("peer", "beta").ref("t", "b"), Refresh("ok")))
            val sent = topics.read(sender.topicOf("peer"), 0, 0).take(2).toList()
            sent.map {
                CommandMessages.commandOf(
                    EnvelopeCodec.decode(it.value)
                ).type
            }.assert().containsExactly("peer.alpha.Refresh", "peer.beta.Refresh")
        }

    @Test
    fun `a command class sent to an aggregate that decides none of it fails before the broker`() {
        val gamma = Command("third", "third", AggregateType("peer", "gamma").ref("t", "c"), Refresh("no"))
        assertThrows<IllegalStateException> { runBlocking { sender.send(gamma) } }
            .message.assert().contains("not one of peer.gamma")
        runBlocking { topics.offsets(sender.topicOf("peer"), 0).end }.assert().isEqualTo(0L)
    }
}
