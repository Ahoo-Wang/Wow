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

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.SubmissionPublisher

/** The `text/event-stream` reader (the HTML Standard), and the lines of a published body. */
class ServerSentEventsTest {
    private fun events(vararg lines: String) = runBlocking { lines.asList().asFlow().serverSentEvents().toList() }

    @Test
    fun `an event should end at a blank line, its data lines joined`() {
        val read = events("event: row", "id: 7", "data: {\"a\":", "data:1}", "", "data: plain", "")
        read.map { Triple(it.event, it.data, it.id) }.assert().containsExactly(
            Triple("row", "{\"a\":\n1}", "7"),
            Triple("message", "plain", "7"),
        )
    }

    @Test
    fun `comments, unknown fields, events without data and a cut event should not be dispatched`() {
        val read = events(": keep-alive", "retry: 100", "event: end", "", "id", "data", "", "data: cut")
        read.map { it.data to it.id }.assert().containsExactly("" to "")
        events("id: a\u0000b", "data: x", "").single().id.assert().isNull()
        ServerSentEvent("row", "{}", "1").toString().assert().contains("row")
    }

    @Test
    fun `lines should split at LF, CRLF and CR across chunks, as UTF-8`() {
        val decoder = LineDecoder()
        decoder.feed("a\r".toByteArray()).assert().containsExactly("a")
        decoder.feed("\nb\nc\r\r\n".toByteArray()).assert().containsExactly("b", "c", "")
        val snowman = "☃".toByteArray()
        decoder.feed(snowman.copyOfRange(0, 1)).assert().isEmpty()
        decoder.feed(snowman.copyOfRange(1, snowman.size)).assert().isEmpty()
        decoder.rest().assert().isEqualTo("☃")
        decoder.rest().assert().isNull()
    }

    @Test
    fun `a published body should be read as its lines, the last one without a break`(): Unit = runBlocking {
        val publisher = SubmissionPublisher<List<ByteBuffer>>()
        val lines = Thread {
            Thread.sleep(50)
            publisher.submit(listOf(ByteBuffer.wrap("one\ntw".toByteArray()), ByteBuffer.wrap("o\n".toByteArray())))
            publisher.submit(listOf(ByteBuffer.wrap("three".toByteArray())))
            publisher.close()
        }
        lines.start()
        publisher.lines().toList().assert().containsExactly("one", "two", "three")
        lines.join()
    }
}
