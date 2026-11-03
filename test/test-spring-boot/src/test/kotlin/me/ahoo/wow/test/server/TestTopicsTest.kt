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

package me.ahoo.wow.test.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.example.quickstart.BankServer
import me.ahoo.wow.example.quickstart.OpenAccount
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.node.SourceTopics
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment

/** A test server's topics: in its JVM with no configuration, or the ones a test gives it, shared or written first. */
class TestTopicsTest {
    @Test
    fun `a test server should keep its topics in its JVM with no configuration`() {
        wowServer<BankServer>().use { server ->
            server.bean<Environment>().getProperty("wow.transport").assert().isNull()
            val topics = server.bean<InMemoryTopics>()
            server.bean<SourceTopics>().source.assert().isSameAs(topics)
            runBlocking { server.commands(Caller.ANONYMOUS).wait(OpenAccount("Ann"), "ann") }
            eventually("the account's stream published") { topics.messages(ACCOUNTS).isNotEmpty() }
        }
    }

    // region topics
    @Test
    fun `servers given the same topics should share them, which outlive each`() {
        val topics = InMemoryTopics(4)
        wowServer<BankServer>("dev", topics = topics).use { first ->
            first.bean<InMemoryTopics>().assert().isSameAs(topics)
            runBlocking { first.commands(Caller.ANONYMOUS).wait(OpenAccount("Bea"), "bea") }
            wowServer<BankServer>("dev", topics = topics).use { second ->
                second.bean<SourceTopics>().source.assert().isSameAs(topics)
            }
        }
        eventually("the account's stream published") { topics.messages(ACCOUNTS).size == 1 }
    }
    // endregion topics

    @Test
    fun `a server given topics should run its transport in memory`() {
        val topics = InMemoryTopics(1)
        wowServer(BankServer::class.java, topics = topics).use { server ->
            server.bean<Environment>().getProperty("wow.transport").assert().isEqualTo("memory")
            server.bean<SourceTopics>().source.assert().isSameAs(topics)
        }
    }

    private companion object {
        const val ACCOUNTS = "wow.bank.account.state"
    }
}
