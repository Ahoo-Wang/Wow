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

package me.ahoo.wow.example.eshop.ordering.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Outside development, the ordering service without CoSec does not start: it would take its callers from headers any
 * client sets. Its deployment behind the gateway is [OrderingCoSecTest]'s, an integration test on Kafka.
 */
class OrderingWithoutCoSecTest {
    @Test
    fun `a node outside development without CoSec should not start`() {
        // No profile of wow.dev-profiles: a deployment, whatever address its loopback test ports bind (#6972).
        val failure = assertThrows<Exception> { wowServer<OrderingServer>().close() }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains("No durable backend", "wow.dev-profiles")
    }
}
