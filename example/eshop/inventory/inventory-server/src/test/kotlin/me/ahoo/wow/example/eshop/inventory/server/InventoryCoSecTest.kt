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

package me.ahoo.wow.example.eshop.inventory.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Who calls the inventory service: the host's development callers in development, CoSec's behind the gateway (the
 * `cosec` profile, decisions D296, D301); outside development, a node without CoSec does not start rather than take
 * its callers from headers any client sets.
 */
class InventoryCoSecTest {
    @Test
    fun `behind the gateway the caller should be CoSec's`() {
        wowServer<InventoryServer>("cosec").use { server ->
            server.bean<CallerResolver>().assert().isInstanceOf(CoSecCallerResolver::class.java)
        }
    }

    @Test
    fun `in development the callers should be the host's`() {
        wowServer<InventoryServer>("dev").use { server ->
            server.bean<CallerResolver>().assert().isInstanceOf(DevelopmentCallerResolver::class.java)
        }
    }

    @Test
    fun `a node outside development without CoSec should not start`() {
        val failure = assertThrows<Exception> {
            wowServer<InventoryServer>(properties = mapOf("wow.internal.host" to "0.0.0.0")).close()
        }
        generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            .assert().contains("wow.dev-profiles")
    }
}
