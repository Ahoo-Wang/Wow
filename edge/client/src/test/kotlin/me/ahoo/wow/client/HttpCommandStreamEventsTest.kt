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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * D484 N8: a server adds events and `end` reasons only once both clients take them. A user's command stream skips an
 * event this client does not know, never reading it as a result, and takes only an `end` of reason `complete` as the
 * stream's whole: another reason leaves the outcome unknown, as a stream without its `end` does.
 */
class HttpCommandStreamEventsTest {
    data class DeliverItems(val items: List<String>)

    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val ref = BOX.ref("t1", "a b")

    private val user = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
        .user(UserCredential.NONE)
        .contracts(contractOf(BOX, DeliverItems::class.java))
        .build()
        .commands

    private fun stream() = user.stream(CommandRequest.to(ref, DeliverItems(emptyList())))

    private val accepted = WowJson.mapper.writeValueAsString(resultOf(ref, hop = Hop.accepted("mail")))

    @Test
    fun `an event this client does not know should be skipped, never read as a result`(): Unit = runBlocking {
        val committed = WowJson.mapper.writeValueAsString(resultOf(ref))
        peer.reply(
            Reply.events(
                "id: 0\nevent: accepted\ndata: $accepted\n\n",
                "event: progress\ndata: {\"done\":1}\n\n",
                "id: 1\nevent: committed\ndata: $committed\n\n",
                END,
            ),
        )
        stream().toList().map { it.hop.stage }.assert().containsExactly(Stage.ACCEPTED, Stage.COMMITTED)
    }

    @Test
    fun `an end of another reason than complete should leave the outcome unknown, after the results it followed`() {
        listOf(
            """{"reason":"handed-over","resumable":false}""" to "handed-over",
            """{"resumable":false}""" to "none",
            "not json" to "none",
        ).forEach { (end, reason) ->
            peer.reply(Reply.events("id: 0\nevent: accepted\ndata: $accepted\n\n", "event: end\ndata: $end\n\n"))
            val emitted = mutableListOf<CommandResult>()
            val failure = caught<WowProblemException> { runBlocking { stream().toList(emitted) } }
            emitted.map { it.hop.stage }.assert().describedAs(end).containsExactly(Stage.ACCEPTED)
            failure.code.assert().describedAs(end).isEqualTo(FrameworkCodes.COMMAND_UNKNOWN)
            failure.retry.assert().isEqualTo(RetryAdvice.SAME_REQUEST)
            failure.message.assert().contains("ended for a reason this client does not know ($reason)")
        }
    }

    private companion object {
        const val END = "event: end\ndata: {\"reason\":\"complete\",\"resumable\":false}\n\n"
    }
}
