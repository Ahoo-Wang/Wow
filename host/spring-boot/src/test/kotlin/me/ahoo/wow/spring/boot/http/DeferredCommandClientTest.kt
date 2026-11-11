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

package me.ahoo.wow.spring.boot.http

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.spring.boot.OpenAccount
import org.junit.jupiter.api.Test

class DeferredCommandClientTest {
    private val request = CommandRequest.of(OpenAccount("dave"))
    private val sent = mockk<CommandResult>()
    private val waited = mockk<CommandResult>()
    private val client = mockk<CommandClient> {
        coEvery { send(request) } returns sent
        coEvery { wait(request) } returns waited
        every { stream(request) } returns flowOf(sent, waited)
    }

    @Test
    fun `the client should be built on its first use, once, and take every request`() {
        var built = 0
        val deferred = DeferredCommandClient {
            built++
            client
        }
        built.assert().isZero()
        runBlocking {
            deferred.send(request).assert().isSameAs(sent)
            deferred.wait(request).assert().isSameAs(waited)
            deferred.stream(request).toList().assert().containsExactly(sent, waited)
        }
        built.assert().isOne()
    }

    @Test
    fun `a client of another caller should be built on its first use too`() {
        val dave = Caller.of(scope = Scope("dave"), subject = "dave")
        val asDave = mockk<CommandClient> { coEvery { send(request) } returns sent }
        every { client.of(dave) } returns asDave
        var built = 0
        val deferred = DeferredCommandClient {
            built++
            client
        }
        val of = deferred.of(dave)
        built.assert().isZero()
        runBlocking { of.send(request).assert().isSameAs(sent) }
        built.assert().isOne()
    }
}
