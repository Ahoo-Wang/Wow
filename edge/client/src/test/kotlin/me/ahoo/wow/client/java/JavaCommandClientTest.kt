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

package me.ahoo.wow.client.java

import io.mockk.coEvery
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The Java command client's shorthands send the request of a command and the aggregate id they are given. */
class JavaCommandClientTest {
    private val sent = CopyOnWriteArrayList<CommandRequest<*>>()
    private val result = mockk<CommandResult>()
    private val commands = mockk<CommandClient> {
        coEvery { send(any()) } answers {
            sent += firstArg<CommandRequest<*>>()
            result
        }
        coEvery { wait(any()) } answers {
            sent += firstArg<CommandRequest<*>>()
            result
        }
    }
    private val client = JavaCommandClient.of(commands)

    @Test
    fun `every shorthand should send the command to the aggregate id it is given, blocking or asynchronously`() {
        client.send("a", "id-1").assert().isSameAs(result)
        client.sendAsync("b", "id-2").join().assert().isSameAs(result)
        client.sendAndWait("c", "id-3").assert().isSameAs(result)
        client.sendAndWaitAsync("d", "id-4").join().assert().isSameAs(result)
        // Without an id, a creation's: the aggregate's id is the peer's to give.
        client.sendAsync("e").join().assert().isSameAs(result)
        client.sendAndWait("f").assert().isSameAs(result)
        client.sendAndWaitAsync("g").join().assert().isSameAs(result)
        sent.map { it.command to it.aggregateId }.assert().containsExactly(
            "a" to "id-1",
            "b" to "id-2",
            "c" to "id-3",
            "d" to "id-4",
            "e" to null,
            "f" to null,
            "g" to null,
        )
    }
}
